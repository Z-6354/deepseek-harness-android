package com.labteto.dshmobile.browser

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** No late evaluateJavascript fallback: page APIs must exist before website code executes. */
object WebCompatibility {
    fun install(view: WebView, site: Site, seedSessionId: String? = null): ScriptHandler? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
        val script = view.context.assets.open("web-compat.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val seed = seedSessionScript(seedSessionId)
        return WebViewCompat.addDocumentStartJavaScript(view, script + seed, setOf(site.origin))
    }

    /**
     * Seed official + hanui session keys before client plugins construct, so a cold
     * boot can reopen the last non-blank session without waiting for App JS later.
     */
    fun seedSessionScript(sessionId: String?): String {
        if (sessionId.isNullOrBlank() || !SESSION_ID.matches(sessionId)) return ""
        // SESSION_ID already rejects quotes/control chars; embed as a JS string literal.
        return """
;(() => {
  'use strict';
  try {
    var id = '$sessionId';
    var payload = JSON.stringify({ sessionId: id });
    var official = localStorage.getItem('dsh.sessions.current');
    var parsed = null;
    try { parsed = official ? JSON.parse(official) : null; } catch (_) {}
    if (!parsed || typeof parsed.sessionId !== 'string' || !parsed.sessionId) {
      localStorage.setItem('dsh.sessions.current', payload);
    }
    localStorage.setItem('dsh-mobile-hanui.last-session', payload);
  } catch (_) {}
})();
""".trimIndent()
    }

    private val SESSION_ID = Regex("^[\\w.:-]{1,128}$")

    const val UNAVAILABLE = "此 WebView 不支持网页启动前兼容脚本；部分网站可能无法运行。请更新 Android System WebView。"
}
