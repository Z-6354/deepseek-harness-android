package com.labteto.dshmobile

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.work.WorkManager
import com.labteto.dshmobile.browser.BrowserSession
import com.labteto.dshmobile.browser.BrowserStorage
import com.labteto.dshmobile.browser.StartupTrace

/** No DI graph and no native business connection. */
class DshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StartupTrace.mark("process", "applicationOnCreate")
        // Storage suffix must precede any WebView/provider use.
        BrowserStorage.initialize(this)
        // Do not call WebViewCompat.startUpWebView here: on Redmi HyperOS / Android 16 it races the
        // Activity WebView and SIGSEGVs in libwebviewchromium (fault addr 0x18) during first load.
        StartupTrace.mark("process", "browserWarmupSkipped")
        // Retire legacy keep-alive / notifications after the first frame opportunity.
        Handler(Looper.getMainLooper()).post { retireLegacyBackgroundWork() }
    }

    private fun retireLegacyBackgroundWork() {
        runCatching { WorkManager.getInstance(this).cancelUniqueWork("dsh-keep-alive") }
        runCatching {
            stopService(Intent().setClassName(this, "com.labteto.dshmobile.connection.ConnectionService"))
        }
        val prefs = getSharedPreferences("browser_runtime", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("old_notifications_retired", false)) {
            runCatching { getSystemService(NotificationManager::class.java).cancelAll() }
            prefs.edit().putBoolean("old_notifications_retired", true).apply()
        }
    }
}
