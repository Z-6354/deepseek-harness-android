package com.labteto.dshmobile.browser

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Document-start polyfill only. On HyperOS WebView 152, install after the first commit, then
 * [CompatReloadGate] may soft-reload once so a document that lacked native APIs re-enters with
 * the script. Never late-inject the polyfill body into a live document.
 */
object WebCompatibility {
    fun install(view: WebView, site: Site): ScriptHandler? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
        val script = view.context.assets.open("web-compat.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        return WebViewCompat.addDocumentStartJavaScript(view, script, setOf(site.origin))
    }

    /** Returns JSON boolean when probed from the runtime; used only to decide a one-shot reload. */
    const val API_PROBE =
        "(function(){try{return typeof Promise.withResolvers==='function'&&typeof AbortSignal!=='undefined'&&typeof AbortSignal.any==='function'}catch(e){return false}})()"

    const val UNAVAILABLE = "此 WebView 不支持网页启动前兼容脚本；部分网站可能无法运行。请更新 Android System WebView。"
}
