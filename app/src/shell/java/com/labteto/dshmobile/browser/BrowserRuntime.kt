package com.labteto.dshmobile.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.*
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewFeature
import com.labteto.dshmobile.BuildConfig
import java.io.ByteArrayInputStream
import java.io.File

/** Owns the WebView, document lifetime and every callback's lease. No website business state. */
class BrowserRuntime(
    private val context: Context,
    private val cacheDir: File,
    private val canAccess: () -> Boolean,
    private val onEvent: (Event) -> Unit,
    private val onPlatform: (PlatformRequest) -> Unit,
) {
    sealed interface Event {
        data object Starting : Event
        data object Loading : Event
        data class DocumentStarted(val back: Boolean) : Event
        data class Committed(val url: String, val bridgeBound: Boolean) : Event
        data class Interactive(val lease: DocumentLease) : Event
        data class Visual(val lease: DocumentLease) : Event
        data class Failure(val category: LaunchFailure) : Event
        data class HttpError(val status: Int) : Event
        data object RendererGone : Event
        data object Restored : Event
        data object CompatibilityUnavailable : Event
        data object BlockedNavigation : Event
        data object PrivateStorageReset : Event
    }
    sealed interface PlatformRequest {
        data class Bridge(val site: Site, val request: BridgeRequest, val lease: DocumentLease) : PlatformRequest
        data class FilePicker(val lease: DocumentLease, val callback: ValueCallback<Array<Uri>>, val params: WebChromeClient.FileChooserParams) : PlatformRequest
        data class Save(val site: Site, val lease: DocumentLease, val url: String, val disposition: String?, val mime: String?) : PlatformRequest
        data class External(val uri: Uri) : PlatformRequest
    }
    private val document = DocumentEpoch()
    private val diagnostics = RuntimeDiagnostics(BuildConfig.DEBUG, android.os.SystemClock::elapsedRealtime) { sample ->
        android.util.Log.i("LaunchTrace", "browserId=${sample.browserInstance} generation=${sample.documentGeneration} event=${sample.stage} elapsedMs=${sample.elapsedMs} webElapsedMs=${sample.webElapsedMs ?: "unknown"}")
    }
    private val lifecycleBudget = PageLifecycleBudget(android.os.SystemClock::elapsedRealtime)
    private fun trace(stage: RuntimeStage) = diagnostics.mark(stage, lease())
    private var site: Site? = null
    @Volatile var view: WebView? = null
        private set
    private var bridge: WebsiteBridge? = null
    private var compatibility: ScriptHandler? = null
    /** One soft reload after deferred install so a gap WebView's first document picks up document-start. */
    private var compatReloadIssued = false
    private var cache: StaticAssetCache? = null
    private var privateFiles: PrivateFileService? = null
    val generation get() = document.generation
    val committedUrl get() = document.committedInternalUrl
    val sawInternalCommit get() = document.sawInternalCommit
    fun lease() = document.lease()
    fun owns(lease: DocumentLease, requireCommit: Boolean = true): Boolean =
        view != null && document.owns(lease) && canAccess() && (!requireCommit || committedUrl != null)
    private fun current(web: WebView) = web === view && canAccess()

    @SuppressLint("SetJavaScriptEnabled")
    fun start(target: Site, savedState: Bundle?, initialNavigation: String) {
        close()
        document.beginBrowser()
        StartupTrace.mark("browser", "runtimeStart")
        site = target
        trace(RuntimeStage.Begin)
        onEvent(Event.Starting)
        val root = File(StaticAssetCache.root(cacheDir), java.security.MessageDigest.getInstance("SHA-256").digest(target.owner.toByteArray()).joinToString("") { "%02x".format(it) })
        cache = StaticAssetCache(root, diagnostic = { if (BuildConfig.DEBUG) android.util.Log.d("StaticAssetCache", it) })
        val assets = cache
        BrowserEnvironment.markCreated()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val web = WebView(context)
        view = web
        privateFiles = PrivateFileService(context, web, target, ::lease, { owns(it) }, ::reply,
            onStorageReset = { android.os.Handler(android.os.Looper.getMainLooper()).post { if (view === web) onEvent(Event.PrivateStorageReset) } })
        val privateService = privateFiles
        fun isReservedPrivateUrl(url: String): Boolean = privateService?.isReservedPath(url) ?: runCatching {
            val parsed = Uri.parse(url)
            val expected = Uri.parse(target.origin)
            parsed.host.equals(expected.host, ignoreCase = true) &&
                parsed.encodedPath.orEmpty().startsWith(PrivateFileService.READ_PREFIX)
        }.getOrDefault(false)
        var observedStart = false
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false; allowContentAccess = false; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false; mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        // Do not call WebCompatibility.install() before the first load on HyperOS WebView 152 —
        // addDocumentStartJavaScript during Chromium startup SIGSEGVs (fault 0x18) on wannian.
        // Install once after the first commit so it applies to subsequent same-origin navigations.
        compatibility = null
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(browser: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (isReservedPrivateUrl(request.url.toString())) {
                    if (browser !== web) return WebResourceResponse("text/plain", null, 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
                    return privateService?.intercept(request) ?: WebResourceResponse("text/plain", null, 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
                }
                if (request.isForMainFrame || request.method != "GET") return null
                val owner = lease()
                return when (val result = assets?.intercept(target, request.url.toString(), request.requestHeaders,
                    CookieManager.getInstance().getCookie(request.url.toString()),
                    producerAllowed = { current(browser) && assets === cache },
                    consumerAllowed = { current(browser) && document.ownsInitialConsumer(owner) }) ?: StaticAssetCache.Intercept.Skip) {
                    StaticAssetCache.Intercept.Skip -> null
                    is StaticAssetCache.Intercept.Ready -> WebResourceResponse(result.asset.mime, "UTF-8", 200, "OK", result.asset.headers, result.asset.stream)
                    is StaticAssetCache.Intercept.Failed -> WebResourceResponse("text/plain", "UTF-8", result.status, result.reason, mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
                }
            }
            override fun shouldOverrideUrlLoading(browser: WebView, request: WebResourceRequest): Boolean {
                if (!current(browser)) return true
                if (isReservedPrivateUrl(request.url.toString())) return true
                return when (NavigationPolicy.decide(target, request.url.toString())) {
                    Navigation.INTERNAL -> false
                    Navigation.EXTERNAL -> { if (request.isForMainFrame && request.hasGesture()) onPlatform(PlatformRequest.External(request.url)); true }
                    Navigation.BLOCKED -> true
                }
            }
            override fun onPageStarted(browser: WebView, url: String?, favicon: Bitmap?) {
                if (!current(browser)) return
                observedStart = true
                val back = document.documentStarted()
                StartupTrace.mark("document", "firstDocumentStart")
                bridge?.bind(generation)
                privateFiles?.bindDocument(lease())
                trace(RuntimeStage.Started)
                onEvent(Event.DocumentStarted(back))
                if (url == null || NavigationPolicy.decide(target, url) != Navigation.INTERNAL) { browser.stopLoading(); onEvent(Event.BlockedNavigation) }
            }
            override fun onPageCommitVisible(browser: WebView, url: String?) {
                if (current(browser) && url != null && NavigationPolicy.decide(target, url) == Navigation.INTERNAL) commit(url)
            }
            override fun onPageFinished(browser: WebView, url: String?) {
                if (!current(browser)) return
                // flush() on the UI thread has crashed Chromium on some OEM builds; defer.
                browser.post { runCatching { CookieManager.getInstance().flush() } }
                if (url != null && committedUrl == null && NavigationPolicy.decide(target, url) == Navigation.INTERNAL) commit(url)
            }
            override fun onReceivedSslError(browser: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel(); if (current(browser)) onEvent(Event.Failure(LaunchFailure.Certificate))
            }
            override fun onReceivedError(browser: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (current(browser) && request.isForMainFrame) onEvent(Event.Failure(LaunchFailure.Network))
            }
            override fun onReceivedHttpError(browser: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (current(browser) && request.isForMainFrame) onEvent(Event.HttpError(response.statusCode))
            }
            override fun onRenderProcessGone(browser: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (browser === view) { close(); onEvent(Event.RendererGone) } else runCatching { browser.destroy() }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(browser: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val owner = lease()
                if (!current(browser) || !owns(owner)) { callback.onReceiveValue(null); return true }
                onPlatform(PlatformRequest.FilePicker(owner, callback, params)); return true
            }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
        }
        bridge = WebsiteBridge(web, target, { generation }, { committedUrl }) { request, _ ->
            val owner = lease()
            if (current(web) && owns(owner, requireCommit = request.type != "pageReady")) {
                if (request.type == "pageReady") {
                    StartupTrace.mark("document", "pageReady")
                    trace(RuntimeStage.Interactive)
                    if (document.onInteractive(generation) == InteractiveDisposition.ReadyForVisual) onEvent(Event.Interactive(owner))
                } else if (request.type == "pageLifecycle") {
                    BridgeProtocol.lifecycle(request.payload)?.let { event -> if (lifecycleBudget.accept(owner, event)) diagnostics.mark(RuntimeStage.WebLifecycle, owner, event.stage, event.webElapsedMs) }
                } else onPlatform(PlatformRequest.Bridge(target, request, owner))
            }
        }
        // Bind after first navigation start (onPageStarted), not before loadUrl — early WebMessageListener
        // registration races Chromium startup on HyperOS WebView 152.
        web.setDownloadListener { url, _, disposition, mime, _ -> if (current(web)) onPlatform(PlatformRequest.Save(target, lease(), url, disposition, mime)) }
        web.setOnLongClickListener {
            val hit = web.hitTestResult
            val image = hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            val url = hit.extra
            if (!current(web) || !image || url.isNullOrBlank()) false
            else { onPlatform(PlatformRequest.Save(target, lease(), url, null, "image/*")); true }
        }
        val restored = savedState?.let { web.restoreState(it) }
        val owner = lease()
        web.post {
            if (!owns(owner, requireCommit = false)) return@post
            if (restored != null && restored.size > 0 && web.url?.let { NavigationPolicy.decide(target, it) == Navigation.INTERNAL } == true) {
                if (!observedStart) { bridge?.bind(generation); privateFiles?.bindDocument(lease()); trace(RuntimeStage.Restored); onEvent(Event.Restored) }
            } else { trace(RuntimeStage.Loading); onEvent(Event.Loading); web.loadUrl(initialNavigation) }
        }
    }
    private fun commit(url: String) {
        if (!document.onInternalCommit(url)) return
        trace(RuntimeStage.Committed)
        ensureCompatibilityInstalled()
        onEvent(Event.Committed(url, bridge?.isBound(generation) == true))
        if (document.consumePendingInteractive()) onEvent(Event.Interactive(lease()))
    }
    private fun ensureCompatibilityInstalled() {
        if (compatibility != null) return
        val web = view ?: return
        val target = site ?: return
        compatibility = runCatching { WebCompatibility.install(web, target) }.getOrNull()
        if (compatibility == null) {
            onEvent(Event.CompatibilityUnavailable)
            return
        }
        // First committed document never ran the just-registered document-start script.
        // Probe native APIs; reload once only when the polyfill is actually required.
        web.evaluateJavascript(WebCompatibility.API_PROBE) { raw ->
            if (!current(web)) return@evaluateJavascript
            val present = raw == "true"
            if (!CompatReloadGate.shouldIssueReload(present, compatReloadIssued)) return@evaluateJavascript
            compatReloadIssued = true
            StartupTrace.mark("document", "compatReload")
            web.reload()
        }
    }
    fun requestVisual(owner: DocumentLease) {
        val browser = view ?: return
        if (!owns(owner)) return
        browser.postVisualStateCallback(owner.documentGeneration, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { if (requestId == owner.documentGeneration && owns(owner)) { StartupTrace.mark("document", "visualComplete"); trace(RuntimeStage.Visual); onEvent(Event.Visual(owner)) } }
        })
    }
    fun navigate(url: String) { val target = site ?: return; if (canAccess() && NavigationPolicy.decide(target, url) == Navigation.INTERNAL) view?.loadUrl(url) }
    fun goBack() {
        val browser = view ?: return
        if (!browser.canGoBack()) return
        document.onGoBack()
        browser.goBack()
    }
    fun saveState(bundle: Bundle) { view?.saveState(bundle) }
    fun setForeground(value: Boolean) { if (value) view?.onResume() else { if (view != null) CookieManager.getInstance().flush(); view?.onPause() } }
    fun reply(owner: DocumentLease, json: String) {
        val browser = view ?: return
        if (!owns(owner)) return
        BridgeReplyDelivery.deliver(browser, owner.documentGeneration, { generation }, json) { owns(owner) && browser === view }
    }
    fun privateFileCapabilities() = privateFiles?.capabilities()
    fun handlePrivateFileRequest(request: BridgeRequest, owner: DocumentLease, foreground: Boolean): Boolean =
        privateFiles?.handle(request, owner, foreground) ?: false
    fun close() {
        document.retireBrowser()
        privateFiles?.close(); privateFiles = null
        compatibility?.remove(); compatibility = null
        compatReloadIssued = false
        view?.let { browser -> (browser.parent as? android.view.ViewGroup)?.removeView(browser); browser.stopLoading(); browser.webChromeClient = null; browser.destroy() }
        view = null; bridge = null; site = null
        cache?.let { runCatching { it.close() } }; cache = null
    }
}
