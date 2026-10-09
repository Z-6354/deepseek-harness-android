package com.labteto.dshmobile.browser

import android.content.Intent
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class BrowserRuntimeDeviceTest {
    @Test fun arrayBufferProbeAndPrivateFileReadRoundTripUseTheRealWebViewBridge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var supported = false
        instrumentation.runOnMainSync {
            supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        }
        org.junit.Assume.assumeTrue("WebView ArrayBuffer listener support is required for this fixture", supported)
        val host = instrumentation.startActivitySync(Intent(context, RuntimeFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RuntimeFixtureActivity
        val site = Site(name = "Private file fixture", entryUrl = "https://runtime.invalid/")
        val html = context.assets.open("private-files-fixture.html").bufferedReader().use { it.readText() }
        lateinit var runtime: BrowserRuntime
        var created = false
        val visual = CountDownLatch(1)
        val load = CountDownLatch(1)
        val committed = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                runtime = BrowserRuntime(host, context.cacheDir, { true }, { event ->
                    if (event is BrowserRuntime.Event.Loading) {
                        load.countDown()
                        runtime.view?.post { runtime.view?.stopLoading(); runtime.view?.loadDataWithBaseURL(site.entryUrl, html, "text/html", "UTF-8", null) }
                    }
                    if (event is BrowserRuntime.Event.Committed) committed.countDown()
                    if (event is BrowserRuntime.Event.Interactive) runtime.requestVisual(event.lease)
                    if (event is BrowserRuntime.Event.Visual) visual.countDown()
                }, { request ->
                    when (request) {
                        is BrowserRuntime.PlatformRequest.Bridge -> {
                            assertTrue("Private control request must be consumed by the runtime service",
                                runtime.handlePrivateFileRequest(request.request, request.lease, foreground = true))
                        }
                        else -> fail("Unexpected request in an isolated local fixture: $request")
                    }
                })
                created = true
                runtime.start(site, null, site.entryUrl)
                host.addContentView(runtime.view, android.view.ViewGroup.LayoutParams(-1, -1))
            }
            assertTrue(load.await(10, TimeUnit.SECONDS))
            assertTrue("Document commit is required before private control calls", committed.await(15, TimeUnit.SECONDS))
            val readyResult = java.util.concurrent.atomic.AtomicReference<String>()
            val readyUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < readyUntil && readyResult.get() != "\"function\"") {
                val sample = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    runtime.view?.evaluateJavascript("typeof window.runPrivateFileFixture") { readyResult.set(it); sample.countDown() } ?: sample.countDown()
                }
                sample.await(1, TimeUnit.SECONDS)
                if (readyResult.get() != "\"function\"") Thread.sleep(50)
            }
            assertEquals("Fixture script must be ready in the committed document", "\"function\"", readyResult.get())
            instrumentation.runOnMainSync { runtime.view?.evaluateJavascript("window.runPrivateFileFixture()", null) }
            val result = java.util.concurrent.atomic.AtomicReference<String>()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < until) {
                val sampleDone = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    runtime.view?.evaluateJavascript("window.__privateFileFixture && JSON.stringify(window.__privateFileFixture)") {
                        result.set(it); sampleDone.countDown()
                    } ?: sampleDone.countDown()
                }
                assertTrue(sampleDone.await(2, TimeUnit.SECONDS))
                if (result.get()?.contains("passed") == true || result.get()?.contains("failed") == true) break
                Thread.sleep(50)
            }
            assertTrue("Fixture reached a real visual callback", visual.await(10, TimeUnit.SECONDS))
            assertTrue("Probe, binary upload, atomic commit and one-use read fixture result=${result.get()}", result.get()?.contains("passed") == true)
        } finally {
            instrumentation.runOnMainSync { if (created) runtime.close(); host.finish() }
        }
    }

    @Test fun localDocumentVisualHotResumeAndRetiredLeases() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val host = instrumentation.startActivitySync(Intent(context, RuntimeFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RuntimeFixtureActivity
        val site = Site(name = "Runtime fixture", entryUrl = "https://runtime.invalid/")
        val events = CopyOnWriteArrayList<BrowserRuntime.Event>()
        var visual = CountDownLatch(1)
        lateinit var runtime: BrowserRuntime
        var runtimeCreated = false
        val html = """<html><body><p>Local runtime fixture</p><script>
            requestAnimationFrame(function(){requestAnimationFrame(function(){
              if(window.HanApp)HanApp.postMessage(JSON.stringify({version:1,id:'fixture-ready',type:'pageReady',payload:{}}));
            })});</script></body></html>"""
        try {
            instrumentation.runOnMainSync {
                runtime = BrowserRuntime(host, context.cacheDir, { true }, { event ->
                    events += event
                    if (event is BrowserRuntime.Event.Loading) {
                        runtime.view?.post { runtime.view?.stopLoading(); runtime.view?.loadDataWithBaseURL(site.entryUrl, html, "text/html", "UTF-8", null) }
                    }
                    if (event is BrowserRuntime.Event.Interactive) runtime.requestVisual(event.lease)
                    if (event is BrowserRuntime.Event.Visual) visual.countDown()
                }, { fail("Unexpected native platform request") })
                runtimeCreated = true
                runtime.start(site, null, site.entryUrl)
                host.addContentView(runtime.view, android.view.ViewGroup.LayoutParams(-1, -1))
            }
            assertTrue("First local document reached a real WebView visual callback", visual.await(20, TimeUnit.SECONDS))
            lateinit var oldLease: DocumentLease
            lateinit var firstView: WebView
            instrumentation.runOnMainSync {
                oldLease = runtime.lease(); firstView = runtime.view!!
                assertTrue(runtime.owns(oldLease))
                val generation = runtime.generation
                val count = events.size
                runtime.setForeground(false); runtime.setForeground(true)
                assertSame(firstView, runtime.view)
                assertEquals(generation, runtime.generation)
                assertEquals(count, events.size)
                runtime.saveState(android.os.Bundle())
                visual = CountDownLatch(1)
                runtime.start(site, null, site.entryUrl)
                host.addContentView(runtime.view, android.view.ViewGroup.LayoutParams(-1, -1))
                assertFalse(runtime.owns(oldLease))
                runtime.requestVisual(oldLease)
            }
            assertTrue("New browser's local document reached its own visual callback", visual.await(20, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertNotSame(firstView, runtime.view)
                assertNotEquals(oldLease.browserInstance, runtime.lease().browserInstance)
                assertFalse(runtime.owns(oldLease))
                val current = runtime.lease()
                runtime.close(); assertFalse(runtime.owns(current)); assertNull(runtime.view)
            }
            assertEquals(2, events.count { it is BrowserRuntime.Event.Starting })
            assertEquals(2, events.count { it is BrowserRuntime.Event.Visual })
        } finally { instrumentation.runOnMainSync { if (runtimeCreated) runtime.close(); host.finish() } }
    }
}
