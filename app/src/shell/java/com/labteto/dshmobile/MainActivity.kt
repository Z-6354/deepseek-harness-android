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
import com.labteto.dshmobile.update.AppUpdateInstaller
import com.labteto.dshmobile.update.AppUpdateLocator
import com.labteto.dshmobile.update.AppUpdateOffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** A generic single-owner website container. No native login, RPC, business DTO or fallback. */
class MainActivity : AppCompatActivity() {
    private lateinit var repository: SiteRepository
    private lateinit var credentialStore: PlatformCredentialStore
    private lateinit var root: LinearLayout
    private lateinit var frame: FrameLayout
    private lateinit var status: TextView
    private val runtime by lazy { BrowserRuntime(this, cacheDir,
        { !isFinishing && !isDestroyed && !repository.cleanupPending }, ::onRuntimeEvent, ::onPlatformRequest) }
    private val browser get() = runtime.view
    private lateinit var launchCover: FrameLayout
    private lateinit var launchSpinner: ImageView
    private lateinit var launchVersion: TextView
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
        // Display budget only — pipeline keeps loading; late pageReady may still uncover.
        // If a document already committed (login/home under the cover), uncover so the
        // opaque spinner cannot hide a 200 auth page forever after the IP-gate change.
        launch.onDisplayBudgetExceeded(hadInternalCommit = runtime.committedUrl != null || runtime.sawInternalCommit)
        applyLaunchSurface()
        if (launch.retryVisible) {
            launchHint.visibility = android.view.View.VISIBLE
            launchHint.text = if (launch.networkError)
                "无法连接到网站。请检查网络后，长按左上角打开设置并重新加载。"
            else
                "加载超时。长按左上角打开设置并重新加载。"
        }
    }
    private var site: Site? = null
    private val generation get() = runtime.generation
    private val committedUrl get() = runtime.committedUrl
    private val surfaceReady get() = launch.surfaceReady
    private var restoredState: Bundle? = null
    private var loading = true
    private var download = SafeDownload()
    private var lastNotification = 0L
    private var downloading = false
    private var downloadJob: kotlinx.coroutines.Job? = null
    private var downloadOperation = 0L
    @Volatile private var downloadVisibilityEpoch = 0L
    private val cleanupObserverKey = java.util.UUID.randomUUID().toString()
    private var cleanupNonce: String? = null
    private val updateSource by lazy { AppUpdateLocator() }
    private val updateInstaller by lazy { AppUpdateInstaller(this) }
    private var updateJob: kotlinx.coroutines.Job? = null
    private var updateDownloadJob: kotlinx.coroutines.Job? = null
    private var updatePrompted = false
    private var pendingUpdateDialog: AppUpdateOffer? = null
    private var pendingInstallOffer: AppUpdateOffer? = null
    private var updateProgressDialog: AlertDialog? = null
    private var updateProgressBar: ProgressBar? = null
    private var updateProgressLabel: TextView? = null
    private val installPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val offer = pendingInstallOffer ?: return@registerForActivityResult
        if (updateInstaller.canInstall()) downloadAndInstallUpdate(offer)
        else status.text = "未授予安装权限，无法更新应用。"
    }

    private data class Picker(val lease: DocumentLease, val callback: ValueCallback<Array<Uri>>, var canceled: Boolean = false)
    private var picker: Picker? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = picker ?: return@registerForActivityResult
        picker = null
        if (pending.canceled) return@registerForActivityResult
        val data = result.data
        val uris = if (result.resultCode == RESULT_OK && runtime.owns(pending.lease) && data != null &&
            data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            val candidates = (data.clipData?.let { clip -> (0 until clip.itemCount.coerceAtMost(FileSelectionPolicy.MAX_FILES + 1)).map { clip.getItemAt(it).uri } }
                ?: listOfNotNull(data.data)).distinct()
            if ((data.clipData?.itemCount ?: 1) > FileSelectionPolicy.MAX_FILES || candidates.any { !readable(it) }) null else candidates.toTypedArray()
        } else null
        pending.callback.onReceiveValue(uris?.takeIf { it.isNotEmpty() })
    }

    private data class PermissionReply(val lease: DocumentLease, val id: String)
    private var permissionReply: PermissionReply? = null
    private var siteConfirmation = false
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val pending = permissionReply ?: return@registerForActivityResult
        permissionReply = null
        if (!valid(pending.lease) || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return@registerForActivityResult
        deliverBridgeReply(pending.lease, BridgeProtocol.reply(pending.id, buildJsonObject { put("granted", notificationGranted()) }))
    }

    private data class DownloadRequest(val lease: DocumentLease, val site: Site, val url: String)
    private var pendingDownload: DownloadRequest? = null
    private val destination = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pending = pendingDownload ?: return@registerForActivityResult
        pendingDownload = null
        if (uri == null) return@registerForActivityResult
        if (!valid(pending.lease) || !FileSelectionPolicy.allowedUri(uri.toString())) { deletePartial(uri); return@registerForActivityResult }
        val visibilityEpoch = downloadVisibilityEpoch
        lifecycleScope.launch {
            try {
                lifecycle.withResumed {
                    if (visibilityEpoch == downloadVisibilityEpoch && foreground() && valid(pending.lease) && !downloading) beginDownload(pending, uri)
                    else deletePartial(uri)
                }
            } catch (_: Exception) { deletePartial(uri) }
        }
    }

    private fun offerSave(site: Site, url: String, contentDisposition: String?, mime: String?) {
        val request = DownloadRequest(runtime.lease(), site, url)
        val image = mime?.startsWith("image/") == true || url.startsWith("blob:", true) || url.startsWith("data:", true)
        AlertDialog.Builder(this).setMessage(if (image) "保存此图片？" else "保存此文件？最大 25 MiB。仅浏览器可用的导出方式可能无法使用。")
            .setPositiveButton("保存") { _, _ ->
                if (foreground() && valid(request.lease) && pendingDownload == null && !downloading) {
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
        val operation = ++downloadOperation
        downloadJob = lifecycleScope.launch {
            fun ownsDownload() = operation == downloadOperation && runtime.owns(pending.lease) && downloadVisibilityEpoch == visibilityEpoch
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
                if (valid(pending.lease)) status.text = "文件已保存"
            } catch (_: Exception) {
                deletePartial(uri)
                if (valid(pending.lease)) status.text = "下载不可用。仅支持同源 GET 文件，最大 25 MiB。"
            } finally { lease.cancel(); if (operation == downloadOperation) { downloading = false; downloadJob = null } }
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
        val owner = runtime.lease()
        val raw = awaitDocumentResult({ runtime.owns(owner) }) { done ->
            web.post {
                if (!runtime.owns(owner)) { done(null); return@post }
                web.evaluateJavascript(script, done)
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
        StartupTrace.mark("activity", "mainOnCreate")
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
        title = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 33) {
            setTaskDescription(
                ActivityManager.TaskDescription.Builder()
                    .setLabel(getString(R.string.app_name))
                    .build(),
            )
        } else {
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(getString(R.string.app_name)))
        }
        // Exit system splash; avoid remove()-immediately tricks that race WebView on HyperOS.
        // iconView can be null on OEM builds without a splash icon drawable.
        splash.setOnExitAnimationListener { provider ->
            provider.iconView?.clearAnimation()
            provider.remove()
        }
        launch.releaseSystemSplash()
        applyLaunchSurface()
        if (BrowserStorage.initializationFailed) {
            launch.failed(LaunchFailure.Storage)
            applyLaunchSurface()
            status.text = "无法初始化隔离浏览存储。请重启应用；在此之前无法打开网站。"
            return
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val ime = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                when {
                    ime -> WindowInsetsControllerCompat(window, root).hide(WindowInsetsCompat.Type.ime())
                    browser?.canGoBack() == true -> { runtime.goBack() }
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
            catch (_: Exception) { loading = false; launch.failed(LaunchFailure.Storage); applyLaunchSurface(); status.text = "数据迁移未能完成。请重启应用重试；在此之前无法打开网站。" }
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
                    if (message.isBlank() || message == site?.name || message.startsWith("正在加载")) return
                    // Some OEMs crash with BadTokenException if Toast runs before the window is ready.
                    if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
                    runCatching { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
                }
            })
        }
        frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        launchCover = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.WHITE) }
        val density = resources.displayMetrics.density
        // Phase 2: spinner + version. Phase 1 system splash owns the large brand icon.
        val spinnerSize = (144 * density).toInt()
        launchSpinner = ImageView(this).apply {
            setImageResource(R.drawable.loading_spinner)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "正在加载"
        }
        launchVersion = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF8B8E96.toInt())
            textSize = 13f
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        val launchCenter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            addView(launchSpinner, LinearLayout.LayoutParams(spinnerSize, spinnerSize))
            addView(launchVersion, LinearLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
        }
        launchCover.addView(
            launchCenter,
            FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER),
        )
        launchHint = TextView(this).apply {
            visibility = android.view.View.GONE
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF17181C.toInt())
            textSize = 15f
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), (32 * density).toInt())
        }
        launchCover.addView(launchHint, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL))
        frame.addView(launchCover, FrameLayout.LayoutParams(-1, -1))
        launchCover.setOnLongClickListener { showSettings(); true }
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

    /** Starts in the background while the launch cover is up; dialog waits until the page uncovers. */
    private fun startAutoUpdateCheck() {
        if (updatePrompted || updateJob?.isActive == true) return
        android.util.Log.i("DshaUpdate", "check start local=${BuildConfig.VERSION_CODE}/${BuildConfig.VERSION_NAME}")
        updateJob = lifecycleScope.launch {
            try {
                // Never block the main thread — concurrent WebView start + main I/O crashes HyperOS Chromium.
                val offer = withContext(Dispatchers.IO) { updateSource.latest() }
                if (offer == null) {
                    android.util.Log.i("DshaUpdate", "no offer")
                    return@launch
                }
                android.util.Log.i("DshaUpdate", "offer=${offer.versionCode}/${offer.versionName} url=${offer.apkUrl}")
                if (offer.versionCode <= BuildConfig.VERSION_CODE || updatePrompted) {
                    android.util.Log.i("DshaUpdate", "skip older-or-prompted")
                    return@launch
                }
                // HyperOS/WebView SIGSEGV if AlertDialog opens while Chromium is still starting.
                if (canShowUpdateUi()) {
                    showUpdateDialog(offer)
                } else {
                    pendingUpdateDialog = offer
                    android.util.Log.i("DshaUpdate", "pending until page settled")
                }
            } catch (error: Exception) {
                android.util.Log.w("DshaUpdate", "check failed", error)
            }
        }
    }

    private fun canShowUpdateUi(): Boolean =
        !isFinishing && !isDestroyed &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
            window?.decorView?.isAttachedToWindow == true &&
            // Wait until launch cover settles — dialog over mid-load WebView crashes Redmi HyperOS (SIGSEGV in libwebviewchromium).
            (launch.surfaceReady || launch.surface == LaunchSurface.Failed)

    private fun maybeShowPendingUpdate() {
        val offer = pendingUpdateDialog ?: return
        if (updatePrompted || !canShowUpdateUi()) return
        pendingUpdateDialog = null
        android.util.Log.i("DshaUpdate", "showing pending dialog")
        showUpdateDialog(offer)
    }

    private fun showUpdateDialog(offer: AppUpdateOffer) {
        if (updatePrompted || isFinishing || isDestroyed) return
        if (!canShowUpdateUi()) {
            pendingUpdateDialog = offer
            return
        }
        updatePrompted = true
        android.util.Log.i("DshaUpdate", "dialog show ${offer.versionName}")
        runOnUiThread {
            if (isFinishing || isDestroyed || !canShowUpdateUi()) {
                updatePrompted = false
                pendingUpdateDialog = offer
                return@runOnUiThread
            }
            runCatching {
                val web = browser
                web?.onPause()
                AlertDialog.Builder(this)
                    .setTitle("发现新版本 ${offer.versionName}")
                    .setMessage("当前 v${BuildConfig.VERSION_NAME}，可更新到 v${offer.versionName}。")
                    .setPositiveButton("更新") { _, _ ->
                        web?.onResume()
                        downloadAndInstallUpdate(offer)
                    }
                    .setNegativeButton("关闭") { _, _ -> web?.onResume() }
                    .setOnCancelListener { web?.onResume() }
                    .setCancelable(true)
                    .show()
            }.onFailure { error ->
                browser?.onResume()
                updatePrompted = false
                pendingUpdateDialog = offer
                android.util.Log.w("DshaUpdate", "dialog show failed", error)
            }
        }
    }

    private fun downloadAndInstallUpdate(offer: AppUpdateOffer) {
        if (updateDownloadJob?.isActive == true) return
        updateDownloadJob = lifecycleScope.launch {
            showUpdateProgress("正在下载 ${offer.versionName}…", indeterminate = true)
            when (
                val prepared = updateInstaller.prepare(offer) { downloaded, total ->
                    runOnUiThread {
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            showUpdateProgress("正在下载 ${offer.versionName}… $percent%", percent = percent)
                        } else {
                            val mb = downloaded / (1024 * 1024)
                            showUpdateProgress("正在下载 ${offer.versionName}… ${mb} MB", indeterminate = true)
                        }
                    }
                }
            ) {
                is AppUpdateInstaller.PrepareResult.NeedPermission -> {
                    dismissUpdateProgress()
                    pendingInstallOffer = offer
                    status.text = "请允许安装未知应用后继续更新。"
                    try {
                        installPermissionLauncher.launch(prepared.settingsIntent)
                    } catch (_: Exception) {
                        pendingInstallOffer = null
                        status.text = "无法打开安装权限设置。"
                    }
                }
                is AppUpdateInstaller.PrepareResult.Failed -> {
                    dismissUpdateProgress()
                    status.text = prepared.message
                    if (canShowUpdateUi()) {
                        runCatching {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("更新失败")
                                .setMessage(prepared.message)
                                .setPositiveButton("关闭", null)
                                .show()
                        }
                    }
                }
                is AppUpdateInstaller.PrepareResult.Ready -> {
                    showUpdateProgress("正在打开系统安装界面…", indeterminate = true)
                    try {
                        startActivity(updateInstaller.installIntent(prepared.uri))
                        dismissUpdateProgress()
                        status.text = "请在系统界面确认安装。"
                    } catch (_: Exception) {
                        dismissUpdateProgress()
                        status.text = "无法打开系统安装界面。"
                    }
                }
            }
        }
    }

    private fun showUpdateProgress(message: String, percent: Int? = null, indeterminate: Boolean = false) {
        if (!canShowUpdateUi()) return
        if (updateProgressDialog == null) {
            val density = resources.displayMetrics.density
            val pad = (24 * density).toInt()
            val label = TextView(this).apply {
                text = message
                setPadding(0, 0, 0, (12 * density).toInt())
            }
            val bar = ProgressBar(this).apply {
                max = 100
                isIndeterminate = true
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(label, LinearLayout.LayoutParams(-1, -2))
                addView(bar, LinearLayout.LayoutParams(-1, -2))
            }
            updateProgressLabel = label
            updateProgressBar = bar
            val dialog = AlertDialog.Builder(this)
                .setTitle("应用更新")
                .setView(box)
                .setCancelable(false)
                .create()
            val shown = runCatching { dialog.show() }.isSuccess
            if (!shown) {
                updateProgressLabel = null
                updateProgressBar = null
                return
            }
            updateProgressDialog = dialog
        }
        updateProgressLabel?.text = message
        updateProgressBar?.apply {
            if (indeterminate || percent == null) {
                isIndeterminate = true
            } else {
                isIndeterminate = false
                progress = percent
            }
        }
    }

    private fun dismissUpdateProgress() {
        updateProgressDialog?.dismiss()
        updateProgressDialog = null
        updateProgressBar = null
        updateProgressLabel = null
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
            runtime.navigate(raw)
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

    private fun createBrowser(target: Site) {
        if (isDestroyed || isFinishing || repository.cleanupPending || BrowserEnvironment.isCleaning) return
        if (repository.owner != target.owner) { status.text = "当前浏览环境与目标网站不一致。请重新加载或清除本地数据。"; return }
        destroyBrowser()
        site = target; siteConfirmation = false
        val saved = restoredState.also { restoredState = null }
        val tap = notificationUrl(intent, target)
        runtime.start(target, if (tap == null) saved else null, tap ?: repository.lastDocument(target) ?: target.entryUrl)
        runtime.view?.let { frame.addView(it, 0, FrameLayout.LayoutParams(-1, -1)) }
    }

    private fun onRuntimeEvent(event: BrowserRuntime.Event) {
        when (event) {
            BrowserRuntime.Event.Starting -> { launch.onBrowserRecreated() }
            BrowserRuntime.Event.Loading -> {
                armLaunchDeadline()
                startAutoUpdateCheck()
            }
            is BrowserRuntime.Event.DocumentStarted -> {
                retirePlatformTasks()
                if (!event.back) launch.onDocumentStarted()

            }
            is BrowserRuntime.Event.Committed -> {
                site?.let { repository.rememberDocument(it, event.url) }
                status.text = site?.name.orEmpty() + if (!event.bridgeBound) " · 系统增强已暂停；请重新加载首页以恢复。" else ""

            }
            is BrowserRuntime.Event.Interactive -> {
                if (launch.onInteractive()) runtime.requestVisual(event.lease)
            }
            is BrowserRuntime.Event.Visual -> {
                launch.onVisualComplete(launch.browserId)
                maybeShowPendingUpdate()
                startAutoUpdateCheck()
            }
            is BrowserRuntime.Event.Failure -> { if (!surfaceReady) launch.failed(event.category); status.text = "网站无法加载。请检查地址、证书和网络后重新加载。" }
            is BrowserRuntime.Event.HttpError -> {
                // Auth/gate surfaces must uncover even when the body is JSON (403/401/503).
                if (launch.coverVisible && event.status in setOf(401, 403, 503)) launch.onGateRejected()
                status.text = "网站返回 HTTP ${event.status}。请确认服务器登录或访问控制。"
            }
            BrowserRuntime.Event.RendererGone -> {
                retirePlatformTasks()
                if (!surfaceReady) launch.failed(LaunchFailure.Network)
                status.text = "网页渲染进程已停止。请重新加载首页；此前操作不会自动重放。"
            }
            BrowserRuntime.Event.Restored -> {
                launch.onRestoreWithoutReload()
                cancelLaunchDeadline()
                maybeShowPendingUpdate()
                startAutoUpdateCheck()
            }
            BrowserRuntime.Event.CompatibilityUnavailable -> Toast.makeText(this, WebCompatibility.UNAVAILABLE, Toast.LENGTH_LONG).show()
            BrowserRuntime.Event.BlockedNavigation -> status.text = "已阻止打开该地址"
        }
        applyLaunchSurface()
    }

    private fun onPlatformRequest(request: BrowserRuntime.PlatformRequest) {
        when (request) {
            is BrowserRuntime.PlatformRequest.Bridge -> bridge(request.site, request.request, request.lease)
            is BrowserRuntime.PlatformRequest.External -> openExternal(request.uri)
            is BrowserRuntime.PlatformRequest.Save -> {
                if (!foreground() || !valid(request.lease) || pendingDownload != null || downloading) return
                val allowed = NavigationPolicy.decide(request.site, request.url) == Navigation.INTERNAL ||
                    (request.mime?.startsWith("image/") == true && (request.url.startsWith("blob:", true) || request.url.startsWith("data:", true)))
                if (allowed) offerSave(request.site, request.url, request.disposition, request.mime)
                else status.text = "文件下载仅支持同源 HTTPS GET。图片可长按保存。"
            }
            is BrowserRuntime.PlatformRequest.FilePicker -> {
                val callback = request.callback; val params = request.params
                if (!foreground() || !valid(request.lease) || picker != null || params.isCaptureEnabled) { callback.onReceiveValue(null); status.text = "请使用系统文件选择器选择已有文件。"; return }
                val mimeTypes = params.acceptTypes.filter { it.contains('/') && it.length < 128 && it.none(Char::isISOControl) }.toTypedArray()
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (mimeTypes.size == 1) mimeTypes[0] else "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                if (mimeTypes.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                picker = Picker(request.lease, callback)
                try { filePicker.launch(intent) } catch (_: Exception) { picker = null; callback.onReceiveValue(null) }
            }
        }
    }

    private fun bridge(target: Site, request: BridgeRequest, epoch: DocumentLease) {
        if (isFinishing || isDestroyed || browser == null || repository.cleanupPending) return
        if (request.type != "pageReady" && !valid(epoch)) return
        if (request.type.startsWith("privateFiles.")) {
            runtime.handlePrivateFileRequest(request, epoch, foreground())
            return
        }
        fun reply(result: JsonObject? = null, error: String? = null) {
            if (!runtime.owns(epoch)) return
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
            deliverBridgeReply(epoch, BridgeProtocol.reply(request.id, result, error))
        }
        if (request.type != "pageReady" && BridgeProtocol.requiresForeground(request.type) && !foreground()) { reply(error = "foreground_required"); return }
        when (request.type) {
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
                put("pageLifecycleVersion", 1)
                put("websiteSwitch", true)
                put("credentialStorage", true)
                put("notifications", notificationGranted()); put("notificationPermission", Build.VERSION.SDK_INT >= 33)
                put("filePicker", true); put("downloads", true); put("mediaCapture", false); put("bridgeVersion", 1)
                put("downloadModes", buildJsonArray { add("same-origin-get") })
                runtime.privateFileCapabilities()?.let { put("privateFiles", it) }
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

    private fun deliverBridgeReply(epoch: DocumentLease, json: String) = runtime.reply(epoch, json)
    private fun notificationGranted() = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(this).areNotificationsEnabled()
    private fun valid(lease: DocumentLease) = runtime.owns(lease)
    private fun forwardCornerTouch(event: android.view.MotionEvent) {
        val view = browser ?: return
        val copy = android.view.MotionEvent.obtain(event)
        copy.offsetLocation(settingsCorner.left.toFloat(), settingsCorner.top.toFloat())
        view.dispatchTouchEvent(copy)
        copy.recycle()
    }

    private fun applyLaunchSurface() {
        launchCover.visibility = if (launch.coverVisible) android.view.View.VISIBLE else android.view.View.GONE
        settingsCorner.visibility = if (launch.coverVisible) android.view.View.GONE else android.view.View.VISIBLE
        // Cover settle is the HyperOS-safe moment to present a pending update dialog.
        if (!launch.coverVisible || launch.surface == LaunchSurface.Failed) maybeShowPendingUpdate()
        // Spinner + version while the launch cover is up (including slow first-load timeout).
        val showSpinner = launch.brandCover || launch.lastFailure == LaunchFailure.Timeout
        if (showSpinner) {
            launchSpinner.visibility = android.view.View.VISIBLE
            launchVersion.visibility = android.view.View.VISIBLE
            launchVersion.text = "v${BuildConfig.VERSION_NAME}"
            if (launchSpinner.animation == null) {
                launchSpinner.startAnimation(
                    android.view.animation.AnimationUtils.loadAnimation(this, R.anim.loading_spinner_anim),
                )
            }
        } else {
            launchSpinner.clearAnimation()
            launchSpinner.visibility = android.view.View.GONE
            launchVersion.visibility = android.view.View.GONE
        }
        when {
            launch.lastFailure == LaunchFailure.Certificate -> {
                launchHint.visibility = android.view.View.VISIBLE
                launchHint.text = "证书校验失败，已阻止打开该网站。"
            }
            launch.networkError -> {
                launchHint.visibility = android.view.View.VISIBLE
                launchHint.text = "无法连接到网站。请检查网络后，长按左上角打开设置并重新加载。"
            }
            launch.retryVisible && launch.lastFailure != LaunchFailure.Timeout -> {
                launchHint.visibility = android.view.View.VISIBLE
                launchHint.text = "加载失败。长按左上角打开设置并重新加载。"
            }
            launch.coverVisible -> {
                // Phase 2: always show loading copy under the spinner while the cover is up.
                launchHint.visibility = android.view.View.VISIBLE
                launchHint.text = "正在加载中"
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
        // Budget starts at loadUrl. Soft tip near the 5s cold TTFI target; does not stop loading.
        launchHandler.postDelayed(slowHint, 4_500)
        launchHandler.postDelayed(launchDeadline, 8_000)
    }
    private fun cancelLaunchDeadline() {
        launchHandler.removeCallbacks(slowHint)
        launchHandler.removeCallbacks(launchDeadline)
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
        site?.let { target -> notificationUrl(intent, target)?.let { url -> if (!repository.cleanupPending) runtime.navigate(url) } }
    }
    private fun retirePlatformTasks() {
        downloadOperation++; downloadJob?.cancel(); downloadJob = null; downloading = false
        download.cancel()
        picker?.takeIf { !it.canceled }?.let { it.canceled = true; it.callback.onReceiveValue(null) }
    }
    private fun destroyBrowser() { retirePlatformTasks(); runtime.close() }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        runtime.saveState(outState)
    }
    override fun onResume() {
        super.onResume()
        runtime.setForeground(true)
        launch.onResume()
        applyLaunchSurface()
        maybeShowPendingUpdate()
    }
    override fun onPause() {
        downloadVisibilityEpoch++
        runtime.setForeground(false)
        download.cancel()
        super.onPause()
    }
    override fun onStop() { downloadVisibilityEpoch++; download.cancel(); super.onStop() }
    override fun onDestroy() {
        dismissUpdateProgress()
        updateDownloadJob?.cancel()
        launchHandler.removeCallbacksAndMessages(null)
        BrowserEnvironment.detach(cleanupObserverKey)
        cleanupNonce = null
        destroyBrowser()
        super.onDestroy()
    }
}
