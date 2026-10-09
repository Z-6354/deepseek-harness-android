package com.labteto.dshmobile.browser

import android.webkit.WebView
import androidx.webkit.*

/** One registration per observed GET document; stale listener closures carry their own epoch. */
class WebsiteBridge(
    private val view: WebView,
    private val site: Site,
    private val currentEpoch: () -> Long,
    private val committedUrl: () -> String?,
    private val onRequest: (BridgeRequest, JavaScriptReplyProxy) -> Unit,
) {
    private var installed = false
    private var boundEpoch: Long? = null
    fun isBound(epoch: Long): Boolean = installed && boundEpoch == epoch
    fun bind(expectedEpoch: Long) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        if (installed && boundEpoch == expectedEpoch) return
        if (installed) WebViewCompat.removeWebMessageListener(view, "HanApp")
        WebViewCompat.addWebMessageListener(view, "HanApp", setOf(site.origin)) { web, message, source, mainFrame, proxy ->
            if (web !== view) return@addWebMessageListener
            val request = runCatching { BridgeProtocol.parse(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
            if (BridgeProtocol.allowed(site, source.toString(), mainFrame, committedUrl(), currentEpoch(), expectedEpoch, request.type)) {
                onRequest(request, proxy)
            }
        }
        installed = true
        boundEpoch = expectedEpoch
    }
}
