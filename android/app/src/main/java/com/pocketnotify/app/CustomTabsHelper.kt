package com.pocketnotify.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

object CustomTabsHelper {
    private const val CHROME_PKG = "com.android.chrome"

    private fun isPackageInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun openUrl(context: Context, rawUrl: String) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val uri = Uri.parse(url)

        val lightColors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(Color.WHITE)
            .setSecondaryToolbarColor(Color.WHITE)
            .setNavigationBarColor(Color.WHITE)
            .setNavigationBarDividerColor(Color.TRANSPARENT)
            .build()

        val darkColors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(Color.parseColor("#121212"))
            .setSecondaryToolbarColor(Color.parseColor("#121212"))
            .setNavigationBarColor(Color.parseColor("#121212"))
            .setNavigationBarDividerColor(Color.TRANSPARENT)
            .build()

        val customTabs = CustomTabsIntent.Builder()
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
            .setDefaultColorSchemeParams(lightColors)
            .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, darkColors)
            .setUrlBarHidingEnabled(true)
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .build()

        if (context !is Activity) {
            customTabs.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (isPackageInstalled(context, CHROME_PKG)) {
            customTabs.intent.setPackage(CHROME_PKG)
        }

        try {
            customTabs.launchUrl(context, uri)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }
}

class LinkRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        if (url.startsWith("http://") || url.startsWith("https://")) {
            CustomTabsHelper.openUrl(this, url)
        } else {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
        finish()
    }

    companion object {
        const val EXTRA_URL = "extra_url"

        fun createIntent(context: Context, url: String): Intent =
            Intent(context, LinkRouterActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
    }
}
