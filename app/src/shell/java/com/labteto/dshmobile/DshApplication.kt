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

/** No DI graph and no native business connection. */
class DshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Storage suffix must precede any WebView/provider use.
        BrowserStorage.initialize(this)
        // Kick Chromium startup off the UI-critical path; do not wait before creating the real WebView.
        BrowserSession.warmup(this)
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
