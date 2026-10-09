package com.labteto.dshmobile.browser

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.labteto.dshmobile.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BrowserShellDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun apkHasNoNativeBusinessRuntime() {
        listOf("com.labteto.dshmobile.connection.ConnectionManager", "com.labteto.dshmobile.data.SessionStore", "com.labteto.dshmobile.notify.NotificationObserver", "com.labteto.dshmobile.di.AppModule", "com.labteto.dshmobile.core.wire.RpcTransport").forEach {
            assertTrue(it, runCatching { context.classLoader.loadClass(it) }.exceptionOrNull() is ClassNotFoundException)
        }
        assertNotNull(context.classLoader.loadClass("com.labteto.dshmobile.connection.KeepAliveWorker"))
        assertNull(context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SERVICES).services?.firstOrNull { it.name.endsWith("ConnectionService") })
    }

    @Test fun cleanupPendingAndTargetSurviveRepositoryRecreation() {
        val prefsName = "browser_test_${UUID.randomUUID()}"
        try {
            val one = SiteRepository(context, prefsName)
            val site = Site(name = "Local test", entryUrl = "https://local.test/")
            one.save(site); one.beginCleanup(site)
            val resumed = SiteRepository(context, prefsName)
            assertTrue(resumed.cleanupPending)
            assertEquals(site.owner, resumed.targetOwner)
            assertNull(resumed.owner)
            assertEquals(site.id, resumed.active()?.id)
            assertTrue(resumed.completeCleanup(resumed.pendingCleanup()!!))
            val completed = SiteRepository(context, prefsName)
            assertFalse(completed.cleanupPending)
            assertEquals(site.owner, completed.owner)
        } finally { context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun cleanupRecoveryReconcilesPreferencesCommittedBeforeStorageFence() {
        val prefsName = "browser_test_recovery_fence_${UUID.randomUUID()}"
        val site = Site(name = "Recovery A", entryUrl = "https://recovery-a.test/")
        try {
            val repository = SiteRepository(context, prefsName)
            repository.save(site)
            val store = repository.privateFileStore(site.owner)
            val partition = store.partitionId(PrivateFileStore.NAMESPACE, "a".repeat(64))!!
            publishForCleanupTest(store, partition, "b".repeat(64), byteArrayOf(7, 8, 9))
            val nonce = UUID.randomUUID().toString()
            val epoch = BrowserStorage.suffix ?: "legacy-default"
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
                .putBoolean("cleanupPending", true).putString("targetOwner", site.owner)
                .putString("cleanupNonce", nonce).putString("cleanupEpoch", epoch)
                .putString("active", site.id).putBoolean("fresh", false).commit()

            val reopened = SiteRepository(context, prefsName)
            val transaction = reopened.pendingCleanup()!!
            assertTrue("recovery installs the missing durable fence before deleting", reopened.preparePrivateFileCleanup(transaction))
            assertFalse(store.isAvailable())
            assertNull(store.lookup(partition, "b".repeat(64)))
            assertTrue(reopened.completeCleanup(transaction))
            assertTrue(store.isAvailable())
        } finally { context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun cleanupRecoveryReblocksAfterFenceFinishedBeforePreferencesCleared() {
        val prefsName = "browser_test_recovery_preferences_${UUID.randomUUID()}"
        val site = Site(name = "Recovery B", entryUrl = "https://recovery-b.test/")
        try {
            val repository = SiteRepository(context, prefsName)
            repository.save(site)
            val store = repository.privateFileStore(site.owner)
            val partition = store.partitionId(PrivateFileStore.NAMESPACE, "c".repeat(64))!!
            publishForCleanupTest(store, partition, "d".repeat(64), byteArrayOf(1, 2, 3))
            val transaction = repository.beginCleanup(site)
            assertTrue(repository.preparePrivateFileCleanup(transaction))
            assertTrue(store.finishCleanup(transaction.nonce)) // Simulate process death before clearing preferences.
            val finishedEpoch = store.currentEpoch()

            val reopened = SiteRepository(context, prefsName)
            assertEquals(transaction, reopened.pendingCleanup())
            assertTrue(reopened.preparePrivateFileCleanup(transaction))
            assertNotEquals("retry must advance the fence epoch", finishedEpoch, store.currentEpoch())
            assertTrue(reopened.completeCleanup(transaction))
            assertFalse(reopened.cleanupPending)
            assertTrue(store.isAvailable())
            assertNull(store.lookup(partition, "d".repeat(64)))
        } finally { context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun publishForCleanupTest(store: PrivateFileStore, partition: String, key: String, bytes: ByteArray) {
        val write = store.beginWrite(partition, key, bytes.size.toLong(), "application/octet-stream")!!
        assertEquals(bytes.size.toLong(), store.append(write, 0, bytes))
        assertNotNull(store.commit(write))
    }

    @Test fun actualProviderFeatureDeterminesUnsupportedSwitch() {
        instrumentation.runOnMainSync {
            val supported = BrowserEnvironment.canDelete()
            val expected = if (supported) EnvironmentDecision.CLEAN else EnvironmentDecision.UNSUPPORTED
            assertEquals(expected, EnvironmentPolicy.decide("old-owner", "new-owner", true, false, supported))
        }
    }
    @Test fun unsupportedLocalLogoutPersistsBlockBeforeInvalidatingAndCannotReuseOwner() {
        val prefsName = "browser_test_logout_${UUID.randomUUID()}"
        try {
            val repository = SiteRepository(context, prefsName)
            val site = Site(name = "Test", entryUrl = "https://local.test/")
            repository.save(site); val transaction = repository.beginCleanup(site); assertTrue(repository.completeCleanup(transaction))
            var invalidated = false
            repository.blockForLocalLogout {
                assertTrue(repository.cleanupPending)
                assertNull(repository.targetOwner)
                invalidated = true
            }
            assertTrue(invalidated)
            val reopened = SiteRepository(context, prefsName)
            assertTrue(reopened.cleanupPending)
            assertNull(reopened.active())
            assertEquals(EnvironmentDecision.UNSUPPORTED, EnvironmentPolicy.decide(reopened.owner, null, reopened.cleanupPending, false, false))
        } finally { context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun delayedCleanupAcrossRecreationDeliversToLiveObserverWithCapturedOwner() {
        val prefsName = "browser_test_delayed_${UUID.randomUUID()}"
        try {
            val oldRepository = SiteRepository(context, prefsName)
            val target = Site(name = "B", entryUrl = "https://b.test/")
            oldRepository.save(target)
            val transaction = oldRepository.beginCleanup(target)
            val coordinator = CleanupCoordinator()
            lateinit var done: () -> Unit
            var retiredOpened = false
            var currentOpened: String? = null
            coordinator.attachOrStart(transaction, "retired", { done = it }, oldRepository::completeCleanup) { _, _ -> retiredOpened = true }
            coordinator.detach("retired")
            val currentRepository = SiteRepository(context, prefsName)
            coordinator.attachOrStart(currentRepository.pendingCleanup()!!, "live", { error("Deletion must not restart") }, currentRepository::completeCleanup) { captured, success ->
                assertTrue(success)
                assertEquals(captured.targetOwner, currentRepository.owner)
                currentOpened = captured.targetOwner
            }
            assertTrue(runCatching { coordinator.assertCanCreateWriter() }.isFailure)
            done()
            assertFalse(retiredOpened)
            assertEquals(target.owner, currentOpened)
            assertFalse(currentRepository.cleanupPending)
        } finally { context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun delayedCleanupCannotCommitRetargetOrLocalLogoutNonce() {
        for (logout in listOf(false, true)) {
            val prefsName = "browser_test_nonce_${UUID.randomUUID()}"
            try {
                val repository = SiteRepository(context, prefsName)
                val one = Site(name = "B", entryUrl = "https://b.test/")
                val two = Site(name = "C", entryUrl = "https://c.test/")
                repository.save(one); repository.save(two)
                val first = repository.beginCleanup(one)
                val coordinator = CleanupCoordinator()
                lateinit var done: () -> Unit
                var opened = false
                coordinator.attachOrStart(first, "live", { done = it }, repository::completeCleanup) { _, success -> opened = success }
                val replacement = repository.beginCleanup(if (logout) null else two)
                done()
                assertFalse(opened)
                assertTrue(repository.cleanupPending)
                assertEquals(replacement, SiteRepository(context, prefsName).pendingCleanup())
                assertNull(repository.owner)
                assertFalse(repository.completeCleanup(first))
                assertTrue(repository.cleanupPending)
            } finally { context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit() }
        }
    }

    @Test fun ownProviderAndFileUrisAreRejected() {
        assertFalse(FileSelectionPolicy.allowedProvider(context.applicationInfo.uid, context.applicationInfo.uid))
        assertFalse(FileSelectionPolicy.allowedProvider(null, context.applicationInfo.uid))
        assertFalse(FileSelectionPolicy.allowedUri("file://${context.filesDir}/private"))
        assertTrue(FileSelectionPolicy.allowedUri("content://com.android.providers.media.documents/document/1"))
    }

    @Test fun permanentStorageEpochWasReservedBeforeProviderAndDoesNotChangeWithProfiles() {
        if (android.os.Build.VERSION.SDK_INT < 28) { assertNull(BrowserStorage.suffix); return }
        val suffix = BrowserStorage.suffix
        assertNotNull(suffix)
        assertFalse(BrowserStorage.initializationFailed)
        assertEquals(suffix, context.getSharedPreferences("browser_storage_epoch_v1", Context.MODE_PRIVATE).getString("suffix", null))
        SiteRepository(context, "browser_test_epoch_probe").sites()
        assertEquals(suffix, BrowserStorage.suffix)
    }

    @Test fun rotationKeepsActivityAndBrowserHostInsteadOfReplayingWebsiteEntry() {
        val application = context.applicationContext as Application
        val created = CopyOnWriteArrayList<Activity>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity is MainActivity) created += activity }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val initial = activity
            val target = if (initial.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            val expected = if (target == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
            instrumentation.runOnMainSync { initial.requestedOrientation = target }
            waitUntil { initial.resources.configuration.orientation == expected }
            instrumentation.waitForIdleSync()
            assertFalse(initial.isDestroyed)
            assertEquals(1, created.size)
            assertSame(initial, created.single())
        } finally {
            instrumentation.runOnMainSync { activity?.let { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; it.finish() } }
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    @Test fun actualWebMessageListenerRejectsIframeAndStaleEpochThenRebinds() {
        var supported = false
        instrumentation.runOnMainSync { supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
        assumeTrue(supported)
        val site = Site(name = "Test", entryUrl = "https://local.test/")
        val received = CopyOnWriteArrayList<String>()
        var epoch = 0L
        var committed: String? = null
        lateinit var view: WebView
        var viewCreated = false
        var fixtureActivity: MainActivity? = null
        val stages = CopyOnWriteArrayList<String>()
        lateinit var binding: WebsiteBridge
        var loaded = CountDownLatch(1)
        try {
            fixtureActivity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val host = fixtureActivity
            instrumentation.runOnMainSync {
                assertTrue("Fixture must be resumed", host.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                view = WebView(host)
                viewCreated = true
                view.settings.javaScriptEnabled = true
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        if (NavigationPolicy.origin(request.url.toString()) != site.origin) return null
                        val html = if (request.url.path == "/frame") "<html><body>same-origin fixture frame</body></html>" else ""
                        return android.webkit.WebResourceResponse("text/html", "UTF-8", java.io.ByteArrayInputStream(html.toByteArray()))
                    }
                    override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) { stages += "started"; epoch++; committed = null }
                    override fun onPageFinished(view: WebView, url: String?) { stages += "finished"; committed = site.entryUrl; loaded.countDown() }
                    override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) { stages += "error:${error.errorCode}:main=${request.isForMainFrame}" }
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                        stages += "rendererGone:crashed=${detail.didCrash()}"
                        (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy(); viewCreated = false
                        return true
                    }
                }
                binding = WebsiteBridge(view, site, { epoch }, { committed }) { request, _ -> received += request.id }
                binding.bind(1)
                host.addContentView(view, android.view.ViewGroup.LayoutParams(-1, -1))
            }
            waitUntil { view.isAttachedToWindow && view.width > 0 && view.height > 0 }
            instrumentation.runOnMainSync {
                assertTrue("Fixture must have a visible window", view.windowVisibility == android.view.View.VISIBLE)
                stages += "attached:${view.width}x${view.height}"
                view.loadDataWithBaseURL(site.entryUrl, "<html><body><iframe src='https://local.test/frame'></iframe></body></html>", "text/html", "UTF-8", null)
            }
            assertTrue("First document loaded; stages=$stages", loaded.await(15, TimeUnit.SECONDS))
            assertJavascriptSentinel(view, """
                (() => {
                  if (!window.HanApp || typeof HanApp.postMessage !== 'function') return 'missing-main-bridge';
                  if (!frames[0] || frames[0].location.origin !== location.origin) return 'wrong-frame-origin';
                  if (!frames[0].HanApp || typeof frames[0].HanApp.postMessage !== 'function') return 'missing-frame-bridge';
                  HanApp.postMessage(JSON.stringify({version:1,id:'main',type:'capabilities',payload:{}}));
                  frames[0].HanApp.postMessage(JSON.stringify({version:1,id:'iframe',type:'capabilities',payload:{}}));
                  return 'main-and-iframe-posted';
                })()
            """.trimIndent(), "main-and-iframe-posted")
            instrumentation.waitForIdleSync()
            waitUntil { received.contains("main") }
            Thread.sleep(200)
            assertFalse(received.contains("iframe"))
            instrumentation.runOnMainSync { epoch++ }
            assertJavascriptSentinel(view, """
                (() => {
                  if (!window.HanApp || typeof HanApp.postMessage !== 'function') return 'missing-stale-bridge';
                  HanApp.postMessage(JSON.stringify({version:1,id:'stale',type:'capabilities',payload:{}}));
                  return 'stale-posted';
                })()
            """.trimIndent(), "stale-posted")
            instrumentation.waitForIdleSync()
            Thread.sleep(200)
            assertFalse(received.contains("stale"))
            loaded = CountDownLatch(1)
            instrumentation.runOnMainSync {
                binding.bind(epoch + 1)
                view.loadDataWithBaseURL(site.entryUrl, "<html>new GET document</html>", "text/html", "UTF-8", null)
            }
            assertTrue("Rebound document loaded; stages=$stages", loaded.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { view.evaluateJavascript("HanApp.postMessage(JSON.stringify({version:1,id:'new',type:'capabilities',payload:{}}));", null) }
            waitUntil { received.contains("new") }
            assertEquals(listOf("main", "new"), received.toList())
        } finally { instrumentation.runOnMainSync {
            if (viewCreated) { (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() }
            fixtureActivity?.finish()
        } }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < until) Thread.sleep(20)
        assertTrue("Expected bridge delivery", condition())
    }

    private fun assertJavascriptSentinel(view: WebView, script: String, expected: String) {
        val completed = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { result.set(it); completed.countDown() } }
        assertTrue("JavaScript completion callback for $expected", completed.await(5, TimeUnit.SECONDS))
        assertEquals("The exact bridge call must finish before a negative-delivery assertion", "\"$expected\"", result.get())
    }
}
