package com.labteto.dshmobile.browser

import android.content.Context
import android.os.Build
import android.webkit.WebView
import java.util.UUID

/** One permanent installation storage epoch. Never rotate it to implement website switching. */
object BrowserStorage {
    private var preferences: android.content.SharedPreferences? = null
    var initializationFailed = false
        private set
    var suffix: String? = null
        private set
    val isFreshEpoch: Boolean get() = suffix != null && preferences?.getBoolean("claimed", false) == false

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT < 28) {
            initializationFailed = true
            return
        }
        try {
            val prefs = context.getSharedPreferences("browser_storage_epoch_v1", Context.MODE_PRIVATE)
            preferences = prefs
            var value = prefs.getString("suffix", null)
            if (value == null) {
                value = "hanweb_" + UUID.randomUUID().toString().replace("-", "")
                check(prefs.edit().putString("suffix", value).putBoolean("claimed", false).commit())
            }
            // Application calls this before any WebView/provider feature or CookieManager use.
            WebView.setDataDirectorySuffix(value)
            suffix = value
        } catch (_: Exception) { initializationFailed = true }
    }

    fun claimEpoch() {
        preferences?.let { check(it.edit().putBoolean("claimed", true).commit()) }
    }
}
