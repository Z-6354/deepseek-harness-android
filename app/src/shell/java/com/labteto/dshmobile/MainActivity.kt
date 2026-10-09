package com.labteto.dshmobile

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.*
import android.provider.DocumentsContract
import android.webkit.*
import android.widget.*
import java.io.ByteArrayInputStream
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewFeature
import com.labteto.dshmobile.browser.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** A generic single-owner website container. No native login, RPC, business DTO or fallback. */
class MainActivity : AppCompatActivity() {
    private lateinit var repository: SiteRepository
    private lateinit var credentialStore: PlatformCredentialStore
    private lateinit var root: LinearLayout
    private lateinit var frame: FrameLayout
    private lateinit var status: TextView
    private var browser: WebView? = null
    private var staticAssets: StaticAssetCache? = null
    private lateinit var launchCover: FrameLayout
    private lateinit var launchLogo: ImageView
    private lateinit var launchHint: TextView
    private lateinit var settingsCorner: android.view.View
    private var settingsCornerDownX = 0f
    private var settingsCornerDownY = 0f
    private val settingsCornerLongPress = Runnable {
        val now = android.os.SystemClock.uptimeMillis()
        browser?.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0))
        showSettings()
    }
    private val launchHandler = Handler(Looper.getMainLooper())
    private val launch = LaunchCoverState()
    private val slowHint: Runnable = Runnable {
        launch.onSlowHint()
        applyLaunchSurface()
    }
    private val launchDeadline: Runnable = Runnable {
        launch.onDeadline()
        applyLaunchSurface()
        if (launch.retryVisible) {
            launchHint.visibility = android.view.View.VISIBLE
            launchHint.text = if (launch.networkError)
                "无法连接到网站。请检查网络后，长按左上角打开设置并重新加载。"
            else
                "加载超时。长按左上角打开设置并重新加载。不会自动重建页面。"
        }
    }
    private var visualFallback: Runnable? = null
    private var site: Site? = null
    private val generation get() = launch.generation
    private val committedUrl get() = launch.committedInternalUrl
    private val surfaceReady get() = launch.surfaceReady
    private var restoredState: Bundle? = null
    private var loading = true
    private var download = SafeDownload()
    private var lastNotification = 0L
    private var downloading = false
    @Volatile private var downloadVisibilityEpoch = 0L
    private var websiteBridge: WebsiteBridge? = null
    private var compatibilityScript: ScriptHandler? = null
    private val cleanupObserverKey = java.util.UUID.randomUUID().toString()
    private var cleanupNonce: String? = null

    private data class Picker(val generation: Long, val callback: ValueCallback<Array<Uri>>, var canceled: Boolean = false)
    private var picker: Picker? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = picker ?: return@registerForActivityResult
        picker = null
        if (pending.canceled) return@registerForActivityResult
        val data = result.data
        val uris = if (result.resultCode == RESULT_OK && pending.generation == generation && data != null &&
            data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            val candidates = (data.clipData?.let { clip -> (0 until clip.itemCount.coerceAtMost(FileSelectionPolicy.MAX_FILES + 1)).map { clip.getItemAt(it).uri } }
                ?: listOfNotNull(data.data)).distinct()
            if ((data.clipData?.itemCount ?: 1) > FileSelectionPolicy.MAX_FILES || candidates.any { !readable(it) }) null else candidates.toTypedArray()
        } else null
        pending.callback.onReceiveValue(uris?.takeIf { it.isNotEmpty() })
    }

    private data class PermissionReply(val generation: Long, val id: String)
    private var permissionReply: PermissionReply? = null
    private var siteConfirmation = false
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val pending = permissionReply ?: return@registerForActivityResult
        permissionReply = null
        if (!valid(pending.generation) || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return@registerForActivityResult
        deliverBridgeReply(pending.generation, BridgeProtocol.reply(pending.id, buildJsonObject { put("granted", notificationGranted()) }))
    }

    private data class DownloadRequest(val generation: Long, val site: Site, val url: String)
    private var pendingDownload: DownloadRequest? = null
    private val destination = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pending = pendingDownload ?: return@registerForActivityResult
        pendingDownload = null
        if (uri == null) return@registerForActivityResult
        if (!valid(pending.generation) || !FileSelectionPolicy.allowedUri(uri.toString())) { deletePartial(uri); return@registerForActivityResult }
        val visibilityEpoch = downloadVisibilityEpoch
        lifecycleScope.launch {
            try {
                lifecycle.withResumed {
                    if (visibilityEpoch == downloadVisibilityEpoch && foreground() && valid(pending.generation) && !downloading) beginDownload(pending, uri)
                    else deletePartial(uri)
                }
            } catch (_: Exception) { deletePartial(uri) }
        }
    }

    private fun offerSave(site: Site, url: String, contentDisposition: String?, mime: String?) {
        val request = DownloadRequest(generation, site, url)
        val image = mime?.startsWith("image/") == true || url.startsWith("blob:", true) || url.startsWith("data:", true)
        AlertDialog.Builder(this).setMessage(if (image) "保存此图片？" else "保存此文件？最大 25 MiB。仅浏览器可用的导出方式可能无法使用。")
            .setPositiveButton("保存") { _, _ ->
                if (foreground() && valid(request.generation) && pendingDownload == null && !downloading) {
                    pendingDownload = request
                    val filename = URLUtil.guessFileName(url, contentDisposition, mime).replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120).ifBlank { if (image) "image.png" else "download" }
                    try { destination.launch(filename) } catch (_: Exception) { pendingDownload = null; status.text = "系统无法提供保存位置" }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun beginDownload(pending: DownloadRequest, uri: Uri) {
        val lease = download.newLease() // published before scheduling any cookie/network work
        val visibilityEpoch = downloadVisibilityEpoch
        downloading = true
        lifecycleScope.launch {
            fun ownsDownload() = generation == pending.generation && browser != null && downloadVisibilityEpoch == visibilityEpoch
            try {
                lease.ensureActive(::ownsDownload)
                contentResolver.openOutputStream(uri, "w")?.use { stream ->
                    if (pending.url.startsWith("blob:", true) || pending.url.startsWith("data:", true)) {
                        val bytes = readWebImageBytes(pending.url) ?: error("Image bytes unavailable")
                        check(bytes.size <= 8 * 1024 * 1024) { "File exceeds 8 MiB" }
                        lease.write(::ownsDownload) { stream.write(bytes); stream.flush() }
                    } else {
                        download.transfer(pending.site, pending.url,
                            cookie = { url -> lease.ensureActive(::ownsDownload); CookieManager.getInstance().getCookie(url) },
                            owns = ::ownsDownload, output = stream, lease = lease)
                    }
                } ?: error("Destination unavailable")
                if (valid(pending.generation)) status.text = "文件已保存"
            } catch (_: Exception) {
                deletePartial(uri)
                if (valid(pending.generation)) status.text = "下载不可用。仅支持同源 GET 文件，最大 25 MiB。"
            } finally { lease.cancel(); downloading = false }
        }
    }

    private suspend fun readWebImageBytes(url: String): ByteArray? {
        val web = browser ?: return null
        val quoted = org.json.JSONObject.quote(url)
        val script = """
            (function(){
              try {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', $quoted, false);
                xhr.overrideMimeType('text/plain; charset=x-user-defined');
                xhr.send(null);
                if (xhr.status !== 200 && xhr.status !== 0) return '';
                var s = xhr.responseText || '';
                if (s.length > 8388608) return '';
                var out = '';
                for (var i = 0; i < s.length; i++) out += String.fromCharCode(s.charCodeAt(i) & 255);
                return btoa(out);
              } catch (e) { return ''; }
            })()
        """.trimIndent()
        val raw = kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
            web.post {
                web.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value)
                }
            }
        }
        if (raw.isNullOrBlank() || raw == "null" || raw == "\"\"") return null
        val b64 = org.json.JSONTokener(raw).nextValue() as? String ?: return null
        if (b64.isBlank()) return null
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        restoredState = savedInstanceState
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { launch.keepSplash }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.apply { statusBarColor = android.graphics.Color.WHITE; navigationBarColor = android.graphics.Color.WHITE }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        repository = SiteRepository(applicationContext)
        credentialStore = PlatformCredentialStore(applicationContext)
        buildLayout()
        // Do not drop the system splash here: keepSplash stays true until Ready
        // so splash and the brand cover read as one loading stage.
        splash.setOnExitAnimationListener { splashScreenView -> splashScreenView.remove() }
        if (BrowserStorage.initializationFailed) {
            launch.failed(launch.browserId, launch.generation, LaunchFailure.Storage)
            applyLaunchSurface()
            status.text = "无法初始化隔离浏览存储。请重启应用；在此之前无法打开网站。"
            return
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val ime = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                when {
                    ime -> WindowInsetsControllerCompat(window, root).hide(WindowInsetsCompat.Type.ime())
                    browser?.canGoBack() == true -> { launch.onGoBack(); browser?.goBack() }
                    else -> moveTaskToBack(true)
                }
            }
        })
        fun openSite() {
            loading = false
            val previous = repository.active()
            val active = if (previous?.entryUrl == "https://dsh.wannian.fun/" && previous.staticResourcePrefixes.isEmpty())
                previous.copy(staticResourcePrefixes = listOf("/assets/", "/plugins/")).also(repository::save) else previous
            if (repository.cleanupPending) prepare(active) else prepare(active ?: repository.defaultSite())
        }
        if (repository.migrated) openSite()
        else lifecycleScope.launch {
            try { repository.migrate(); openSite() }
            catch (_: Exception) { loading = false; launch.failed(launch.browserId, launch.generation, LaunchFailure.Storage); applyLaunchSurface(); status.text = "数据迁移未能完成。请重启应用重试；在此之前无法打开网站。" }
        }
    }

    private fun buildLayout() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.WHITE) }
        // Keep the website as the entire app surface. Settings entry will be integrated later.
        status = TextView(this).apply {
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val message = s?.toString().orEmpty()
                    if (message.isNotBlank() && message != site?.name && !message.startsWith("正在加载"))
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            })
        }
        frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        launchCover = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.WHITE) }
        launchLogo = ImageView(this).apply { setImageResource(R.drawable.ic_launcher_foreground) }
        val size = (288 * resources.displayMetrics.density).toInt()
        launchCover.addView(launchLogo, FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER))
        launchHint = TextView(this).apply {
            visibility = android.view.View.GONE
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF17181C.toInt())
            textSize = 15f
            setPadding((24 * resources.displayMetrics.density).toInt(), 0, (24 * resources.displayMetrics.density).toInt(), (32 * resources.displayMetrics.density).toInt())
        }
        launchCover.addView(launchHint, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL))
        frame.addView(launchCover, FrameLayout.LayoutParams(-1, -1))
        launchCover.setOnLongClickListener { showSettings(); true }
        val density = resources.displayMetrics.density
        val corner = (44 * density).toInt()
        val cornerInset = (12 * density).toInt()
        settingsCorner = android.view.View(this).apply {
            contentDescription = "设置"
            isClickable = false
            isFocusable = false
            setOnTouchListener { _, event ->
                val slop = android.view.ViewConfiguration.get(this@MainActivity).scaledTouchSlop
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        settingsCornerDownX = event.x
                        settingsCornerDownY = event.y
                        launchHandler.removeCallbacks(settingsCornerLongPress)
                        launchHandler.postDelayed(settingsCornerLongPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                        forwardCornerTouch(event)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = event.x - settingsCornerDownX
                        val dy = event.y - settingsCornerDownY
                        if (dx * dx + dy * dy > slop * slop) launchHandler.removeCallbacks(settingsCornerLongPress)
                        forwardCornerTouch(event)
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        launchHandler.removeCallbacks(settingsCornerLongPress)
                        forwardCornerTouch(event)
                    }
                }
                true
            }
        }
        frame.addView(settingsCorner, FrameLayout.LayoutParams(corner, corner, android.view.Gravity.TOP or android.view.Gravity.START).apply {
            setMargins(cornerInset, cornerInset, 0, 0)
        })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val edges = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(edges.left, edges.top, edges.right, maxOf(edges.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun showSettings() {
        AlertDialog.Builder(this).setTitle("设置")
            .setItems(arrayOf(
                "网站地址：${repository.active()?.entryUrl ?: "https://dsh.wannian.fun/"}",
                "重新加载网站",
                "打开登录链接（一次性 token）",
                "清除本地登录数据",
            )) { _, index ->
                when (index) {
                    0 -> addSite()
                    1 -> reloadWebsite()
                    2 -> openLoginLink()
                    3 -> logoutLocally()
                }
            }.setNegativeButton("关闭", null).show()
    }

    private fun reloadWebsite() {
            if (!loading && site != null && !repository.cleanupPending) {
                AlertDialog.Builder(this@MainActivity).setMessage("重新加载网站首页？未保存的页面改动会丢失。")
                    .setPositiveButton("重新加载") { _, _ -> createBrowser(site!!) }.setNegativeButton("取消", null).show()
            }
    }
    private fun logoutLocally() {
            if (!loading) AlertDialog.Builder(this@MainActivity).setMessage("清除本机浏览数据和已保存的服务器密码？不会确认服务器是否已退出。应用将关闭，请再打开以完成清理。")
                .setPositiveButton("清除") { _, _ -> beginSwitch(null) }.setNegativeButton("取消", null).show()
    }

    private fun addSite() {
        val input = EditText(this).apply { hint = "https://example.com/"; setText(repository.active()?.entryUrl ?: "https://dsh.wannian.fun/"); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        val dialog = AlertDialog.Builder(this).setTitle("修改网站地址").setView(input)
            .setPositiveButton("保存并打开", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val entry = NavigationPolicy.normalizedEntry(input.text.toString())
            if (entry == null) { input.error = "请输入不含账号、查询参数或片段的 HTTPS 地址"; return@setOnClickListener }
            val existing = repository.sites().firstOrNull { it.entryUrl == entry }
            val target = existing ?: Site(name = NavigationPolicy.origin(entry)!!, entryUrl = entry).also { repository.save(it) }
            dialog.dismiss(); prepare(target)
        } }
        dialog.show()
    }

    /** One-shot load of a same-origin URL that may carry ?token=… — never persisted as entryUrl. */
    private fun openLoginLink() {
        val active = site ?: repository.active()
        val view = browser
        if (loading || active == null || view == null || repository.cleanupPending) {
            status.text = "请先打开网站后再粘贴登录链接。"
            return
        }
        val input = EditText(this).apply {
            hint = "https://example.com/?token=…"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val dialog = AlertDialog.Builder(this).setTitle("打开登录链接")
            .setMessage("粘贴 dsh web 打印的带 token 地址。只在本机 WebView 打开一次，不会保存到网站地址。")
            .setView(input).setPositiveButton("打开", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val raw = input.text.toString().trim()
            if (raw.length > 4096 || NavigationPolicy.decide(active, raw) != Navigation.INTERNAL) {
                input.error = "请输入当前网站同源的 HTTPS 登录链接"
                return@setOnClickListener
            }
            dialog.dismiss()
            view.loadUrl(raw)
        } }
        dialog.show()
    }

    private fun prepare(target: Site?) {
        BrowserEnvironment.transaction?.let { running ->
            val capturedTarget = repository.sites().firstOrNull { it.owner == running.targetOwner }
            awaitCleanup(capturedTarget, running)
            return
        }
        if (repository.fresh && repository.owner == null && !repository.cleanupPending && target != null) {
            repository.select(target); repository.claimFresh(target); createBrowser(target); return
        }
        val decision = EnvironmentPolicy.decide(repository.owner, target?.owner, repository.cleanupPending,
            BrowserEnvironment.pageCreated, BrowserEnvironment.canDelete())
        when (decision) {
            EnvironmentDecision.REUSE -> {
                repository.select(target!!)
                if (site?.owner != target.owner || browser == null) createBrowser(target)
                siteConfirmation = false
            }
            EnvironmentDecision.UNSUPPORTED -> {
                siteConfirmation = false
                status.text = "切换网站和清除本地登录需要更新的 Android System WebView（需支持完整清除浏览数据）。请到系统设置中清除本应用存储后再试。"
            }
            EnvironmentDecision.RESTART -> beginSwitch(target)
            EnvironmentDecision.CLEAN -> {
                val transaction = if (!repository.cleanupPending || repository.targetOwner != target?.owner)
                    repository.beginCleanup(target) else repository.pendingCleanup()!!
                awaitCleanup(target, transaction)
            }
        }
    }

    private fun awaitCleanup(target: Site?, transaction: CleanupTransaction) {
        val capturedGeneration = generation
        cleanupNonce = transaction.nonce
        loading = true; status.text = "正在清除浏览数据…"
        BrowserEnvironment.clean(repository, transaction, cleanupObserverKey) { completed, success ->
            if (isDestroyed || isFinishing || capturedGeneration != generation || cleanupNonce != completed.nonce) return@clean
            loading = false
            if (!success || repository.cleanupPending || repository.owner != completed.targetOwner || target?.owner != completed.targetOwner || repository.active()?.owner != completed.targetOwner) {
                siteConfirmation = false
                status.text = "浏览数据未能按目标站点清除完毕。网站仍被阻止打开，请重启应用后重试。"
                return@clean
            }
            if (target != null) createBrowser(target)
            else { site = null; status.text = "本地浏览数据已清除，请重新打开 App 登录。" }
        }
    }

    private fun beginSwitch(target: Site?) {
        if (BrowserEnvironment.isCleaning) { status.text = "正在清除浏览数据，请稍后再切换目标。"; return }
        if (target == null && !credentialStore.clear()) {
            status.text = "未能清除已保存的密码。本地退出尚未完成；请重试，或到系统设置中清除本应用存储。"
            return
        }
        if (!BrowserEnvironment.canDelete()) {
            if (target == null) {
                repository.blockForLocalLogout { destroyBrowser(); site = null }
                getSystemService(NotificationManager::class.java).cancelAll()
                status.text = "已清除本机保存的密码并关闭本地浏览。清理尚未完成。请更新 Android System WebView，或到系统设置中清除本应用存储后再登录。服务器是否已退出未经确认。"
            } else status.text = "请更新 Android System WebView，或到系统设置中清除本应用存储以重置全部数据。"
            return
        }
        repository.beginCleanup(target) // durable before callbacks or old writers are invalidated
        destroyBrowser()
        repository.clearStaticAssets()
        getSystemService(NotificationManager::class.java).cancelAll()
        loading = true; status.text = if (target == null) "已清除本机保存的密码。重置尚未完成，请关闭并重新打开应用。" else "重置尚未完成，请关闭并重新打开应用。"
        AlertDialog.Builder(this).setMessage("清除浏览数据前必须先关闭应用。请从桌面重新打开以继续。")
            .setCancelable(false).setPositiveButton("关闭应用") { _, _ ->
                finishAndRemoveTask(); android.os.Process.killProcess(android.os.Process.myPid())
            }.show()
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createBrowser(target: Site) {
        if (isDestroyed || isFinishing) return
        if (repository.cleanupPending || BrowserEnvironment.isCleaning) {
            status.text = "正在清除浏览数据，请稍后再打开网站。"
            return
        }
        if (repository.owner != target.owner) {
            status.text = "当前浏览环境与目标网站不一致。请长按左上角打开设置，重新加载或清除本地数据。"
            return
        }
        destroyBrowser()
        launch.onBrowserRecreated()
        armLaunchDeadline()
        applyLaunchSurface()
        launchTrace("beginLaunch")
        site = target
        siteConfirmation = false
        staticAssets = StaticAssetCache(java.io.File(StaticAssetCache.root(cacheDir), java.security.MessageDigest.getInstance("SHA-256").digest(target.owner.toByteArray()).joinToString("") { "%02x".format(it) }), diagnostic = { if (BuildConfig.DEBUG) android.util.Log.d("StaticAssetCache", it) })
        BrowserEnvironment.markCreated()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        launchTrace("webviewCreateBegin")
        val view = WebView(this)
        launchTrace("webviewCreateEnd")
        browser = view
        var observedStart = false
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        launchTrace("webviewConfigured")
        compatibilityScript = runCatching {
            WebCompatibility.install(view, target, repository.lastSessionId(target))
        }.getOrNull()
        if (compatibilityScript == null) Toast.makeText(this, WebCompatibility.UNAVAILABLE, Toast.LENGTH_LONG).show()
        val assetCache = staticAssets
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(web: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame || request.method != "GET") return null
                return when (val intercepted = assetCache?.intercept(target, request.url.toString(), request.requestHeaders, CookieManager.getInstance().getCookie(request.url.toString())) {
                    web === browser && site?.owner == target.owner && !repository.cleanupPending
                } ?: StaticAssetCache.Intercept.Skip) {
                    StaticAssetCache.Intercept.Skip -> null
                    is StaticAssetCache.Intercept.Ready -> WebResourceResponse(intercepted.asset.mime, "UTF-8", 200, "OK", intercepted.asset.headers, intercepted.asset.stream)
                    is StaticAssetCache.Intercept.Failed -> WebResourceResponse("text/plain", "UTF-8", intercepted.status, intercepted.reason, mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
                }
            }
            override fun shouldOverrideUrlLoading(web: WebView, request: WebResourceRequest): Boolean {
                if (web !== browser) return true
                return when (NavigationPolicy.decide(target, request.url.toString())) {
                    Navigation.INTERNAL -> false
                    Navigation.EXTERNAL -> { if (request.isForMainFrame && request.hasGesture()) openExternal(request.url); true }
                    Navigation.BLOCKED -> true
                }
            }
            override fun onPageStarted(web: WebView, url: String?, favicon: Bitmap?) {
                if (web !== browser) return
                observedStart = true
                invalidatePage()
                bindBridge(web, target, generation)
                applyLaunchSurface()
                cancelVisualFallback()
                launchTrace("documentStarted", url)
                if (url == null || NavigationPolicy.decide(target, url) != Navigation.INTERNAL) { web.stopLoading(); status.text = "已阻止打开该地址" }
                else if (launch.coverVisible) status.text = "正在加载 ${target.name}…"
            }
            override fun onPageCommitVisible(web: WebView, url: String?) {
                if (web !== browser) return
                if (url != null && NavigationPolicy.decide(target, url) == Navigation.INTERNAL) {
                    launch.onInternalCommit(url)
                    launchTrace("documentCommitted", url)
                    repository.rememberDocument(target, url)
                    applyLaunchSurface()
                    if (launch.awaitVisual) requestUncover(launch.browserId, generation)
                    status.text = target.name + if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && websiteBridge?.isBound(generation) != true)
                        " · 系统增强已暂停；请重新加载首页以恢复。" else ""
                } else {
                    launch.onNonInternalCommit()
                    applyLaunchSurface()
                }
            }
            override fun onPageFinished(web: WebView, url: String?) {
                if (web !== browser) return
                CookieManager.getInstance().flush()
                if (url != null && NavigationPolicy.decide(target, url) == Navigation.INTERNAL && launch.committedInternalUrl == null) {
                    launch.onInternalCommit(url)
                    repository.rememberDocument(target, url)
                    applyLaunchSurface()
                    if (launch.awaitVisual) requestUncover(launch.browserId, generation)
                }
            }
            override fun onReceivedSslError(web: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (web !== browser) return
                if (surfaceReady) { status.text = "证书校验失败，已阻止打开该网站。"; return }
                launch.failed(launch.browserId, generation, LaunchFailure.Certificate)
                cancelLaunchDeadline()
                applyLaunchSurface()
            }
            override fun onReceivedError(web: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (web !== browser || !request.isForMainFrame) return
                if (surfaceReady) { status.text = "网站无法加载。请检查地址和网络后，长按左上角打开设置并重新加载。"; return }
                launch.failed(launch.browserId, generation, LaunchFailure.Network)
                cancelLaunchDeadline()
                applyLaunchSurface()
            }
            override fun onReceivedHttpError(web: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (web !== browser || !request.isForMainFrame) return
                if (launch.coverVisible && response.statusCode in setOf(401, 403)) {
                    cancelLaunchDeadline()
                    cancelVisualFallback()
                    launch.onGateRejected()
                    applyLaunchSurface()
                    status.text = if (response.statusCode == 401)
                        "网站要求登录。当前页面不会发出就绪信号；请用「打开登录链接」粘贴 dsh web 打印的带 token 地址，或确认服务器已启用密码登录页。"
                    else
                        "网站拒绝访问（HTTP 403）。请检查服务器访问控制后重新加载。"
                    return
                }
                if (!launch.coverVisible) status.text = "网站返回 HTTP ${response.statusCode}"
            }
            override fun onRenderProcessGone(web: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (web === browser) { destroyBrowser(); status.text = "网页渲染进程已停止。请重新加载首页；此前操作不会自动重放。" }
                else runCatching { web.destroy() }
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(web: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                if (!foreground() || web != browser || !valid(generation) || picker != null || params.isCaptureEnabled) { callback.onReceiveValue(null); status.text = "请使用系统文件选择器选择已有文件。不支持直接调用相机拍摄。"; return true }
                val mimeTypes = params.acceptTypes.filter { it.contains('/') && it.length < 128 && it.none(Char::isISOControl) }.toTypedArray()
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (mimeTypes.size == 1) mimeTypes[0] else "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                if (mimeTypes.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                picker = Picker(generation, callback)
                try { filePicker.launch(intent) } catch (_: Exception) { picker = null; callback.onReceiveValue(null) }
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
        }
        websiteBridge = WebsiteBridge(view, target, { generation }, { committedUrl }) { request, proxy ->
            if (view === browser) bridge(target, request, proxy)
        }
        bindBridge(view, target, generation)
        view.setDownloadListener { url, _, contentDisposition, mime, _ ->
            if (view !== browser) return@setDownloadListener
            if (!foreground() || !valid(generation) || pendingDownload != null || downloading || NavigationPolicy.decide(target, url) != Navigation.INTERNAL) {
                status.text = "文件下载仅支持同源 HTTPS GET（最大 25 MiB）。图片可长按保存；其它导出请用系统浏览器。"
                return@setDownloadListener
            }
            offerSave(target, url, contentDisposition, mime)
        }
        view.setOnLongClickListener {
            if (view !== browser) return@setOnLongClickListener false
            val hit = view.hitTestResult
            val extra = hit.extra
            val image = hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            if (!image || extra.isNullOrBlank()) return@setOnLongClickListener false
            if (!foreground() || pendingDownload != null || downloading) return@setOnLongClickListener true
            if (NavigationPolicy.decide(target, extra) != Navigation.INTERNAL && !extra.startsWith("blob:", true) && !extra.startsWith("data:", true)) {
                status.text = "仅支持同源图片保存"
                return@setOnLongClickListener true
            }
            offerSave(target, extra, null, "image/*")
            true
        }
        frame.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        val saved = restoredState.also { restoredState = null }
        val restored = saved?.let { view.restoreState(it) }
        val tap = notificationUrl(intent, target)
        view.post {
            if (browser !== view || isDestroyed || isFinishing || repository.cleanupPending) return@post
            when {
                tap != null -> {
                    launchTrace("loadUrl")
                    view.loadUrl(tap)
                }
                restored != null && restored.size > 0 && view.url?.let { NavigationPolicy.decide(target, it) == Navigation.INTERNAL } == true -> {
                    if (!observedStart) {
                        launch.onRestoreWithoutReload()
                        cancelLaunchDeadline()
                        bindBridge(view, target, generation)
                        applyLaunchSurface()
                        launchTrace("restoreWithoutReload")
                    }
                }
                else -> {
                    launchTrace("loadUrl")
                    view.loadUrl(repository.lastDocument(target) ?: target.entryUrl)
                }
            }
        }
    }

    private fun bridge(target: Site, request: BridgeRequest, @Suppress("UNUSED_PARAMETER") proxy: JavaScriptReplyProxy) {
        if (isFinishing || isDestroyed || browser == null || repository.cleanupPending) return
        val epoch = generation
        if (request.type != "pageReady" && !valid(epoch)) return
        fun reply(result: JsonObject? = null, error: String? = null) {
            if (generation != epoch || isFinishing || isDestroyed || browser == null) return
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
            deliverBridgeReply(epoch, BridgeProtocol.reply(request.id, result, error))
        }
        if (request.type != "pageReady" && BridgeProtocol.requiresForeground(request.type) && !foreground()) { reply(error = "foreground_required"); return }
        when (request.type) {
            "pageReady" -> {
                launchTrace("pageReady")
                if (launch.onPageReady()) requestUncover(launch.browserId, epoch)
                persistSessionFromWebView(target)
            }
            "readCredential" -> {
                if (request.payload.isNotEmpty()) { reply(error = "invalid_payload"); return }
                val password = credentialStore.read(target.origin)
                reply(buildJsonObject { put("saved", password != null); if (password != null) put("password", password) })
            }
            "saveCredential" -> {
                val password = BridgeProtocol.credentialPassword(request.payload)
                if (password == null) { reply(error = "invalid_payload"); return }
                if (credentialStore.save(target.origin, password)) reply(buildJsonObject { put("saved", true) })
                else reply(error = "credential_storage_unavailable")
            }
            "changeWebsite" -> {
                if (siteConfirmation || loading || BrowserEnvironment.isCleaning || repository.cleanupPending) {
                    reply(error = "site_change_pending"); return
                }
                val raw = (request.payload["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val entry = raw?.let(NavigationPolicy::normalizedEntry)
                if (entry == null) { reply(error = "invalid_website"); return }
                siteConfirmation = true
                var accepted = false
                AlertDialog.Builder(this).setTitle("切换服务器")
                    .setMessage("打开 $entry？需要在目标服务器重新输入密码。")
                    .setPositiveButton("切换") { _, _ ->
                        if (foreground() && valid(epoch) && !loading && !BrowserEnvironment.isCleaning && !repository.cleanupPending) {
                            val next = repository.sites().firstOrNull { it.entryUrl == entry }
                                ?: Site(name = NavigationPolicy.origin(entry)!!, entryUrl = entry).also { repository.save(it) }
                            accepted = true
                            reply(buildJsonObject { put("accepted", true) }); prepare(next)
                        } else {
                            siteConfirmation = false
                            reply(error = "cancelled")
                        }
                    }.setNegativeButton("取消") { _, _ -> siteConfirmation = false; reply(error = "cancelled") }
                    .setOnDismissListener { if (!accepted) siteConfirmation = false }.show()
            }
            "capabilities" -> reply(buildJsonObject {
                put("websiteSwitch", true)
                put("credentialStorage", true)
                put("notifications", notificationGranted()); put("notificationPermission", Build.VERSION.SDK_INT >= 33)
                put("filePicker", true); put("downloads", true); put("mediaCapture", false); put("bridgeVersion", 1)
                put("downloadModes", buildJsonArray { add("same-origin-get") })
            })
            "requestNotificationPermission" -> {
                if (Build.VERSION.SDK_INT < 33 || notificationGranted()) reply(buildJsonObject { put("granted", notificationGranted()) })
                else if (permissionReply != null) reply(error = "permission_request_pending")
                else {
                    permissionReply = PermissionReply(epoch, request.id)
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            "showNotification" -> {
                val content = BridgeProtocol.notification(target, request.payload)
                when {
                    content == null -> reply(error = "invalid_notification")
                    !notificationGranted() -> reply(error = "permission_denied")
                    SystemClock.elapsedRealtime() - lastNotification < 1000 -> reply(error = "rate_limited")
                    else -> {
                        lastNotification = SystemClock.elapsedRealtime()
                        val manager = getSystemService(NotificationManager::class.java)
                        manager.createNotificationChannel(NotificationChannel("website", "网站通知", NotificationManager.IMPORTANCE_DEFAULT))
                        val open = Intent(this, MainActivity::class.java).setAction("OPEN_WEBSITE_NOTIFICATION")
                            .putExtra("owner", target.owner).putExtra("target", content.targetUrl).putExtra("token", repository.notificationToken)
                            .setData(Uri.parse(WebsiteNotification.tapUri(target.owner, content.tag)))
                        val pending = PendingIntent.getActivity(this, WebsiteNotification.requestCode(target.owner, content.tag), open,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        try {
                            manager.notify(content.tag ?: "website", 1, NotificationCompat.Builder(this, "website")
                                .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(content.title).setContentText(content.body)
                                .setStyle(NotificationCompat.BigTextStyle().bigText(content.body)).setContentIntent(pending).setAutoCancel(true).build())
                            reply(buildJsonObject { put("shown", true) })
                        } catch (_: SecurityException) { reply(error = "permission_denied") }
                    }
                }
            }
        }
    }

    private fun deliverBridgeReply(epoch: Long, json: String) {
        val view = browser ?: return
        BridgeReplyDelivery.deliver(view, epoch, { generation }, json) {
            !isFinishing && !isDestroyed && browser === view && !repository.cleanupPending
        }
    }
    private fun notificationGranted() = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(this).areNotificationsEnabled()
    private fun valid(epoch: Long) = !isFinishing && !isDestroyed && browser != null && generation == epoch && committedUrl != null && !repository.cleanupPending
    private fun forwardCornerTouch(event: android.view.MotionEvent) {
        val view = browser ?: return
        val copy = android.view.MotionEvent.obtain(event)
        copy.offsetLocation(settingsCorner.left.toFloat(), settingsCorner.top.toFloat())
        view.dispatchTouchEvent(copy)
        copy.recycle()
    }

    private fun requestUncover(capturedBrowserId: Long, epoch: Long) {
        cancelVisualFallback()
        val fallback = Runnable {
            launch.onVisualComplete(capturedBrowserId, epoch)
            applyLaunchSurface()
        }
        visualFallback = fallback
        browser?.postVisualStateCallback(epoch, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (visualFallback === fallback) launchHandler.removeCallbacks(fallback)
                launch.onVisualComplete(capturedBrowserId, requestId)
                launchTrace("visualComplete")
                applyLaunchSurface()
            }
        })
        launchHandler.postDelayed(fallback, 800)
    }
    private fun applyLaunchSurface() {
        launchCover.visibility = if (launch.coverVisible) android.view.View.VISIBLE else android.view.View.GONE
        settingsCorner.visibility = if (launch.coverVisible) android.view.View.GONE else android.view.View.VISIBLE
        launchLogo.visibility = if (launch.brandCover) android.view.View.VISIBLE else android.view.View.GONE
        when {
            launch.lastFailure == LaunchFailure.Certificate -> {
                launchHint.visibility = android.view.View.VISIBLE
                launchHint.text = "证书校验失败，已阻止打开该网站。"
            }
            launch.networkError -> {
                launchHint.visibility = android.view.View.VISIBLE
                launchHint.text = "无法连接到网站。请检查网络后，长按左上角打开设置并重新加载。"
            }
            launch.retryVisible -> {
                launchHint.visibility = android.view.View.VISIBLE
                launchHint.text = "加载超时。长按左上角打开设置并重新加载。不会自动重建页面。"
            }
            launch.slowHint || launch.surface == LaunchSurface.DocumentLoading -> {
                launchHint.visibility = android.view.View.VISIBLE
                launchHint.text = if (launch.slowHint) "加载时间较长，请稍候…" else "正在加载…"
            }
            else -> {
                launchHint.text = ""
                launchHint.visibility = android.view.View.GONE
            }
        }
        if (!launch.timeoutArmed) cancelLaunchDeadline()
        if (launch.surfaceReady) cancelLaunchDeadline()
    }
    private fun armLaunchDeadline() {
        cancelLaunchDeadline()
        launchHandler.postDelayed(slowHint, 5_000)
        launchHandler.postDelayed(launchDeadline, 10_000)
    }
    private fun cancelLaunchDeadline() {
        launchHandler.removeCallbacks(slowHint)
        launchHandler.removeCallbacks(launchDeadline)
    }
    private fun cancelVisualFallback() {
        visualFallback?.let(launchHandler::removeCallbacks)
        visualFallback = null
    }
    private fun launchTrace(event: String, url: String? = null) {
        if (!BuildConfig.DEBUG) return
        android.util.Log.i("LaunchTrace", "launchId=${launch.launchId} browserId=${launch.browserId} generation=${launch.generation} event=$event surface=${launch.surface} path=${pathClass(url)}")
    }
    private fun pathClass(url: String?): String {
        if (url.isNullOrEmpty()) return "none"
        val path = runCatching { Uri.parse(url).path }.getOrNull().orEmpty().ifEmpty { "/" }
        return when {
            path == "/" || path == "/index.html" -> "index"
            path.startsWith("/assets/") -> "asset"
            path.startsWith("/plugins/") -> "plugin"
            path.startsWith("/dsh-local-hanaccount/") || path.startsWith("/auth") -> "auth"
            else -> "other"
        }
    }
    private fun foreground() = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    private fun readable(uri: Uri): Boolean = FileSelectionPolicy.allowedUri(uri.toString()) &&
        FileSelectionPolicy.allowedProvider(packageManager.resolveContentProvider(uri.authority ?: "", 0)?.applicationInfo?.uid, applicationInfo.uid) &&
        runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
    private fun deletePartial(uri: Uri) { runCatching { DocumentsContract.deleteDocument(contentResolver, uri) } }
    private fun openExternal(uri: Uri) {
        AlertDialog.Builder(this).setMessage("要在系统浏览器中打开这个外部 HTTPS 链接吗？\n${NavigationPolicy.origin(uri.toString())}")
            .setPositiveButton("打开") { _, _ -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) } }.setNegativeButton("取消", null).show()
    }
    private fun notificationUrl(incoming: Intent?, target: Site): String? {
        if (incoming?.action != "OPEN_WEBSITE_NOTIFICATION" || incoming.getStringExtra("token") != repository.notificationToken || incoming.getStringExtra("owner") != target.owner) return null
        val url = incoming.getStringExtra("target") ?: return null
        incoming.removeExtra("target")
        return NavigationPolicy.notificationTarget(target, url)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        site?.let { target -> notificationUrl(intent, target)?.let { url -> if (!repository.cleanupPending) browser?.loadUrl(url) } }
    }
    private fun invalidatePage() {
        launch.onMainFrameStarted()
        download.cancel()
        picker?.takeIf { !it.canceled }?.let { it.canceled = true; it.callback.onReceiveValue(null) }
    }
    private fun destroyBrowser() {
        download.cancel()
        picker?.takeIf { !it.canceled }?.let { it.canceled = true; it.callback.onReceiveValue(null) }
        compatibilityScript?.remove()
        compatibilityScript = null
        browser?.let { web -> frame.removeView(web); web.stopLoading(); web.webChromeClient = null; web.destroy() }
        browser = null
        staticAssets?.let { runCatching { it.close() } }; staticAssets = null
        websiteBridge = null
    }
    private fun bindBridge(view: WebView, target: Site, capturedEpoch: Long) {
        if (view === browser && site?.owner == target.owner) websiteBridge?.bind(capturedEpoch)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        browser?.saveState(outState)
    }
    override fun onResume() {
        super.onResume()
        browser?.onResume()
        launch.onResume()
        applyLaunchSurface()
    }
    private fun persistSessionFromWebView(target: Site) {
        val view = browser ?: return
        if (site?.owner != target.owner) return
        val script = """
            (function(){
              try {
                var keys = ['dsh-mobile-hanui.last-session', 'dsh.sessions.current'];
                for (var i = 0; i < keys.length; i++) {
                  var raw = localStorage.getItem(keys[i]);
                  if (!raw) continue;
                  var parsed = JSON.parse(raw);
                  if (parsed && typeof parsed.sessionId === 'string' && parsed.sessionId) return parsed.sessionId;
                }
              } catch (e) {}
              return '';
            })()
        """.trimIndent()
        view.evaluateJavascript(script) { raw ->
            if (isFinishing || isDestroyed || site?.owner != target.owner) return@evaluateJavascript
            val value = raw?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "null" } ?: return@evaluateJavascript
            if (Regex("^[\\w.:-]{1,128}$").matches(value)) repository.rememberSessionId(target, value)
        }
    }

    override fun onPause() {
        site?.let(::persistSessionFromWebView)
        if (browser != null) CookieManager.getInstance().flush()
        downloadVisibilityEpoch++
        browser?.onPause()
        download.cancel()
        super.onPause()
    }
    override fun onStop() { downloadVisibilityEpoch++; download.cancel(); super.onStop() }
    override fun onDestroy() { launchHandler.removeCallbacksAndMessages(null); BrowserEnvironment.detach(cleanupObserverKey); cleanupNonce = null; destroyBrowser(); super.onDestroy() }
}
