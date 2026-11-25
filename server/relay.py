"""Pocket Notify local relay server (zero external dependencies).

Provides:
- GET  /api/v1/health
- POST /api/v1/push
- GET  /api/v1/events?since_id=0&limit=50
- GET  /api/v1/stream?since_id=0   (Server-Sent Events)

All endpoints require `Authorization: Bearer <token>`.
"""

import argparse
from contextlib import closing
from datetime import datetime, timezone
import hmac
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import queue
import secrets
import signal
import sqlite3
import threading
from urllib.parse import parse_qs, urlparse

VERSION = "0.1.0"
MAX_BODY_BYTES = 64 * 1024
ALLOWED_PRIORITIES = {"low", "default", "high", "urgent"}


def utc_now():
    return datetime.now(timezone.utc).isoformat()


class EventStore:
    def __init__(self, db_path, max_history=2000):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.max_history = max(100, int(max_history))
        self._lock = threading.Lock()
        self._subscribers = set()
        self._init_db()

    def _connect(self):
        conn = sqlite3.connect(str(self.db_path), timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL;")
        return conn

    def _init_db(self):
        with self._lock, closing(self._connect()) as conn:
            with conn:
                conn.executescript(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        channel TEXT NOT NULL,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        priority TEXT NOT NULL,
                        click_url TEXT,
                        dedup_key TEXT UNIQUE,
                        meta_json TEXT,
                        created_at TEXT NOT NULL
                    );
                    CREATE INDEX IF NOT EXISTS idx_events_created ON events(created_at);
                    """
                )

    @staticmethod
    def _row_to_dict(row):
        meta = None
        if row["meta_json"]:
            try:
                meta = json.loads(row["meta_json"])
            except Exception:
                meta = None
        return {
            "id": row["id"],
            "channel": row["channel"],
            "title": row["title"],
            "body": row["body"],
            "priority": row["priority"],
            "click_url": row["click_url"] or "",
            "dedup_key": row["dedup_key"] or "",
            "meta": meta,
            "created_at": row["created_at"],
        }

    def push(self, *, channel, title, body, priority="default", click_url="", dedup_key=None, meta=None):
        channel = (str(channel or "default").strip() or "default")[:64]
        title = str(title or "").strip()[:200]
        body = str(body or "").strip()[:4000]
        if not title and not body:
            raise ValueError("Either title or body must be non-empty")
        priority = str(priority or "default").strip().lower()
        if priority not in ALLOWED_PRIORITIES:
            priority = "default"
        click_url = str(click_url or "").strip()[:1000]
        if click_url and not click_url.startswith(("http://", "https://")):
            raise ValueError("click_url must start with http:// or https://")
        dedup_clean = str(dedup_key).strip()[:160] if dedup_key else None
        if dedup_clean == "":
            dedup_clean = None
        meta_json = json.dumps(meta, ensure_ascii=False) if meta is not None else None
        now = utc_now()

        created = True
        with self._lock, closing(self._connect()) as conn:
            with conn:
                if dedup_clean is not None:
                    existing = conn.execute(
                        "SELECT * FROM events WHERE dedup_key = ?", (dedup_clean,)
                    ).fetchone()
                    if existing is not None:
                        return self._row_to_dict(existing), False

                cur = conn.execute(
                    """
                    INSERT INTO events (channel, title, body, priority, click_url, dedup_key, meta_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (channel, title, body, priority, click_url, dedup_clean, meta_json, now),
                )
                event_id = cur.lastrowid
                row = conn.execute("SELECT * FROM events WHERE id = ?", (event_id,)).fetchone()
                # Prune old rows while preserving SQLite AUTOINCREMENT monotonic IDs.
                conn.execute(
                    """
                    DELETE FROM events
                    WHERE id <= (SELECT id FROM events ORDER BY id DESC LIMIT 1 OFFSET ?)
                    """,
                    (self.max_history,),
                )
                event = self._row_to_dict(row)
                subscribers = list(self._subscribers)

        for q in subscribers:
            try:
                q.put_nowait(event)
            except queue.Full:
                pass
        return event, created

    def list_since(self, since_id=0, limit=50):
        since_id = max(0, int(since_id))
        limit = max(1, min(200, int(limit)))
        with self._lock, closing(self._connect()) as conn:
            rows = conn.execute(
                "SELECT * FROM events WHERE id > ? ORDER BY id ASC LIMIT ?",
                (since_id, limit),
            ).fetchall()
            latest = conn.execute("SELECT COALESCE(MAX(id), 0) AS m FROM events").fetchone()["m"]
        return [self._row_to_dict(r) for r in rows], int(latest)

    def latest_id(self):
        with self._lock, closing(self._connect()) as conn:
            return int(conn.execute("SELECT COALESCE(MAX(id), 0) AS m FROM events").fetchone()["m"])

    def subscribe(self):
        q = queue.Queue(maxsize=200)
        with self._lock:
            self._subscribers.add(q)
        return q

    def unsubscribe(self, q):
        with self._lock:
            self._subscribers.discard(q)


class RelayHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, server_address, store, token):
        self.store = store
        self.token = str(token)
        super().__init__(server_address, RelayHandler)


class RelayHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        # Keep stdout clean & never log Authorization headers.
        ts = utc_now()
        print(f"[{ts}] {self.address_string()} {fmt % args}", flush=True)

    def _authorized(self):
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return False
        provided = auth[7:].strip()
        return bool(provided) and hmac.compare_digest(provided, self.server.token)

    def _send_json(self, status, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(int(status))
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def _require_auth(self):
        if not self._authorized():
            self._send_json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return False
        return True

    def do_GET(self):
        parsed = urlparse(self.path)
        if not self._require_auth():
            return

        if parsed.path == "/api/v1/health":
            self._send_json(
                HTTPStatus.OK,
                {
                    "ok": True,
                    "service": "pocket-notify-relay",
                    "version": VERSION,
                    "latest_id": self.server.store.latest_id(),
                    "server_time": utc_now(),
                },
            )
            return

        if parsed.path == "/api/v1/events":
            qs = parse_qs(parsed.query)
            try:
                since_id = int(qs.get("since_id", ["0"])[0])
                limit = int(qs.get("limit", ["50"])[0])
            except ValueError:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_query_params"})
                return
            events, latest = self.server.store.list_since(since_id=since_id, limit=limit)
            self._send_json(
                HTTPStatus.OK,
                {"events": events, "latest_id": latest, "count": len(events)},
            )
            return

        if parsed.path == "/api/v1/stream":
            qs = parse_qs(parsed.query)
            try:
                since_id = int(qs.get("since_id", ["0"])[0])
            except ValueError:
                self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_since_id"})
                return
            self._handle_sse(since_id)
            return

        self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        if not self._require_auth():
            return

        if parsed.path != "/api/v1/push":
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_content_length"})
            return
        if length <= 0 or length > MAX_BODY_BYTES:
            self._send_json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "invalid_payload_size"})
            return

        raw = self.rfile.read(length)
        try:
            data = json.loads(raw.decode("utf-8"))
            if not isinstance(data, dict):
                raise ValueError("Payload must be a JSON object")
        except Exception:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": "invalid_json"})
            return

        try:
            event, created = self.server.store.push(
                channel=data.get("channel", "default"),
                title=data.get("title", ""),
                body=data.get("body", ""),
                priority=data.get("priority", "default"),
                click_url=data.get("click_url", ""),
                dedup_key=data.get("dedup_key"),
                meta=data.get("meta"),
            )
        except ValueError as exc:
            self._send_json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            return

        self._send_json(
            HTTPStatus.CREATED if created else HTTPStatus.OK,
            {"ok": True, "created": created, "event": event},
        )

    def _handle_sse(self, since_id):
        q = self.server.store.subscribe()
        try:
            self.send_response(HTTPStatus.OK)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.send_header("X-Accel-Buffering", "no")
            self.end_headers()

            last_sent = since_id
            # Catch up any missed events atomically after subscribing.
            missed, _ = self.server.store.list_since(since_id=last_sent, limit=100)
            for ev in missed:
                self._write_sse_event(ev)
                last_sent = max(last_sent, ev["id"])
            self.wfile.write(b": connected\n\n")
            self.wfile.flush()

            while True:
                try:
                    ev = q.get(timeout=25)
                    if ev["id"] <= last_sent:
                        continue
                    self._write_sse_event(ev)
                    last_sent = ev["id"]
                except queue.Empty:
                    self.wfile.write(b": heartbeat\n\n")
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass
        finally:
            self.server.store.unsubscribe(q)

    def _write_sse_event(self, ev):
        payload = json.dumps(ev, ensure_ascii=False)
        frame = f"id: {ev['id']}\nevent: notification\ndata: {payload}\n\n".encode("utf-8")
        self.wfile.write(frame)
        self.wfile.flush()


def load_or_create_config(config_path):
    path = Path(config_path)
    if path.is_file():
        return json.loads(path.read_text(encoding="utf-8"))
    path.parent.mkdir(parents=True, exist_ok=True)
    cfg = {
        "host": "0.0.0.0",
        "port": 18765,
        "token": secrets.token_hex(24),
        "db_path": str(path.parent / "relay.sqlite3"),
        "max_history": 2000,
    }
    path.write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8")
    return cfg


def main():
    parser = argparse.ArgumentParser(description="Pocket Notify local relay server")
    parser.add_argument("--config", required=True, help="Path to relay-config.json (auto-created if missing)")
    args = parser.parse_args()

    cfg = load_or_create_config(args.config)
    token = str(cfg.get("token") or "").strip()
    if not token or token.startswith("REPLACE_"):
        raise SystemExit("Refusing to start with empty or placeholder token in config")

    host = str(cfg.get("host") or "0.0.0.0")
    port = int(cfg.get("port") or 18765)
    db_path = Path(cfg.get("db_path") or "relay.sqlite3")
    if not db_path.is_absolute():
        db_path = Path(args.config).resolve().parent / db_path

    store = EventStore(db_path, max_history=int(cfg.get("max_history") or 2000))
    httpd = RelayHTTPServer((host, port), store, token)

    def _stop(_sig, _frame):
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    print(
        json.dumps(
            {
                "status": "listening",
                "host": host,
                "port": port,
                "version": VERSION,
                "db_path": str(db_path),
            },
            ensure_ascii=False,
        ),
        flush=True,
    )
    try:
        httpd.serve_forever()
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
