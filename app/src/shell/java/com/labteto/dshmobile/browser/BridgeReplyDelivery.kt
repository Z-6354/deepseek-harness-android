package com.labteto.dshmobile.browser

import android.webkit.WebView
import java.util.Base64

/**
 * Outbound bridge replies without [androidx.webkit.JavaScriptReplyProxy.postMessage].
 * WebView 110 (e.g. MuMu Trichrome) can SIGSEGV inside JsReplyProxy JNI; that fault is not catchable.
 * Never probe ReplyProxy first — deliver only via evaluateJavascript into HanApp.onmessage.
 */
object BridgeReplyDelivery {
    /** Pure script builder for tests and callers. Null if the payload must not be executed. */
    fun scriptForReply(jsonReply: String): String? {
        if (jsonReply.isEmpty() || jsonReply.length > BridgeProtocol.MAX_MESSAGE) return null
        if (jsonReply.any { it.code < 0x20 && it != '\t' && it != '\n' && it != '\r' }) return null
        val encoded = Base64.getEncoder().encodeToString(jsonReply.toByteArray(Charsets.UTF_8))
        if (encoded.any { ch -> ch !in BASE64_CHARS }) return null
        return "(function(){var h=window.HanApp;if(!h||typeof h.onmessage!=='function')return;" +
            "var b=atob('$encoded'),u=new Uint8Array(b.length);" +
            "for(var i=0;i<b.length;i++)u[i]=b.charCodeAt(i);" +
            "h.onmessage({data:new TextDecoder().decode(u)});})()"
    }

    fun deliver(
        view: WebView,
        expectedGeneration: Long,
        currentGeneration: () -> Long,
        jsonReply: String,
        stillValid: () -> Boolean,
    ) {
        val script = scriptForReply(jsonReply) ?: return
        view.post {
            if (!stillValid() || currentGeneration() != expectedGeneration) return@post
            runCatching { view.evaluateJavascript(script, null) }
        }
    }

    private const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
}
