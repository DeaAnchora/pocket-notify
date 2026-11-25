"""Automated tests for Pocket Notify local relay server."""

import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

import relay


class RelayServerTests(unittest.TestCase):
    def setUp(self):
        self.tmpdir = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmpdir.name) / "test.sqlite3"
        self.token = "test-secret-token-123456"
        self.store = relay.EventStore(self.db_path)
        self.httpd = relay.RelayHTTPServer(("127.0.0.1", 0), self.store, self.token)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.tmpdir.cleanup()

    def _req(self, method, path, body=None, token=None):
        headers = {}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}{path}",
            data=data,
            headers=headers,
            method=method,
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))

    def test_auth_required(self):
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            self._req("GET", "/api/v1/health", token=None)
        self.assertEqual(ctx.exception.code, 401)

        with self.assertRaises(urllib.error.HTTPError) as ctx2:
            self._req("GET", "/api/v1/health", token="wrong-token")
        self.assertEqual(ctx2.exception.code, 401)

    def test_health_push_dedup_and_events(self):
        code, health = self._req("GET", "/api/v1/health", token=self.token)
        self.assertEqual(code, 200)
        self.assertTrue(health["ok"])
        self.assertEqual(health["latest_id"], 0)

        code, res1 = self._req(
            "POST",
            "/api/v1/push",
            body={
                "channel": "tencent-mail",
                "title": "新邮件提醒",
                "body": "发件人：test@example.com\n主题：合同确认",
                "priority": "high",
                "click_url": "https://antigravity.google.com",
                "dedup_key": "mail-1",
            },
            token=self.token,
        )
        self.assertEqual(code, 201)
        self.assertTrue(res1["created"])
        ev1_id = res1["event"]["id"]

        # Duplicate key returns existing event without creating a second row.
        code, res_dup = self._req(
            "POST",
            "/api/v1/push",
            body={
                "channel": "tencent-mail",
                "title": "新邮件提醒",
                "body": "重复",
                "dedup_key": "mail-1",
            },
            token=self.token,
        )
        self.assertEqual(code, 200)
        self.assertFalse(res_dup["created"])
        self.assertEqual(res_dup["event"]["id"], ev1_id)

        # Pull events since 0
        code, evs = self._req("GET", "/api/v1/events?since_id=0", token=self.token)
        self.assertEqual(code, 200)
        self.assertEqual(evs["count"], 1)
        self.assertEqual(evs["events"][0]["title"], "新邮件提醒")

        # Pull events since ev1_id -> empty
        code, evs2 = self._req("GET", f"/api/v1/events?since_id={ev1_id}", token=self.token)
        self.assertEqual(code, 200)
        self.assertEqual(evs2["count"], 0)

    def test_sse_stream_receives_live_event(self):
        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/api/v1/stream?since_id=0",
            headers={"Authorization": f"Bearer {self.token}"},
        )
        received = []

        def reader():
            with urllib.request.urlopen(req, timeout=5) as resp:
                for raw_line in resp:
                    line = raw_line.decode("utf-8").strip()
                    if line.startswith("data: "):
                        received.append(json.loads(line[6:]))
                        break

        t = threading.Thread(target=reader, daemon=True)
        t.start()
        self._req(
            "POST",
            "/api/v1/push",
            body={"channel": "system", "title": "SSE Ping", "body": "Hello Phone"},
            token=self.token,
        )
        t.join(timeout=5)
        self.assertEqual(len(received), 1)
        self.assertEqual(received[0]["title"], "SSE Ping")


if __name__ == "__main__":
    unittest.main()
