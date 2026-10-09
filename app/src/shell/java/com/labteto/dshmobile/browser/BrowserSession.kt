package com.labteto.dshmobile.browser

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import com.labteto.dshmobile.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts WebView provider work early on a background executor.
 * Does not create a spare WebView — that delayed the first real page.
 */
object BrowserSession {
    private val started = AtomicBoolean(false)
    @Volatile var ready = false
        private set
    @Volatile var startedAtElapsed = 0L
        private set
    @Volatile var readyAtElapsed = 0L
        private set

    fun warmup(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        startedAtElapsed = SystemClock.elapsedRealtime()
        val config = WebViewStartUpConfig.Builder(Executors.newSingleThreadExecutor()).build()
        runCatching {
            WebViewCompat.startUpWebView(
                app,
                config,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        ready = true
                        readyAtElapsed = SystemClock.elapsedRealtime()
                        runCatching { CookieManager.getInstance() }
                        if (BuildConfig.DEBUG) {
                            val uiBlocks = result.uiThreadBlockingStartUpLocations?.size ?: 0
                            Log.i(
                                "LaunchTrace",
                                "event=webviewStartUpReady elapsedMs=${readyAtElapsed - startedAtElapsed} uiBlocking=$uiBlocks",
                            )
                        }
                    }

                    override fun onError(error: WebViewStartupException) {
                        readyAtElapsed = SystemClock.elapsedRealtime()
                        if (BuildConfig.DEBUG) {
                            Log.w(
                                "LaunchTrace",
                                "event=webviewStartUpError elapsedMs=${readyAtElapsed - startedAtElapsed} error=${error.javaClass.simpleName}",
                            )
                        }
                        runCatching { CookieManager.getInstance() }
                    }
                },
            )
        }.onFailure { error ->
            if (BuildConfig.DEBUG) {
                Log.w("LaunchTrace", "event=webviewStartUpUnsupported error=${error.javaClass.simpleName}")
            }
            runCatching { CookieManager.getInstance() }
        }
    }
}
