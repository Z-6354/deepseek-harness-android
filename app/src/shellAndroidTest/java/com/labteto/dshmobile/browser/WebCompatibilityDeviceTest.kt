package com.labteto.dshmobile.browser

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.labteto.dshmobile.MainActivity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WebCompatibilityDeviceTest {
    @Test fun documentStartRunsBeforeInlineCodeOnlyForExactOriginAndCanBeRemoved() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val site = Site(name = "Compatibility fixture", entryUrl = "https://compat.test/")
        val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var view: WebView
        var viewCreated = false
        var loaded = CountDownLatch(1)
        var reset: ScriptHandler? = null
        var compatibility: ScriptHandler? = null
        var supported = false
        val stages = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            instrumentation.runOnMainSync {
                view = WebView(host)
                viewCreated = true
                view.settings.javaScriptEnabled = true
                host.addContentView(view, android.view.ViewGroup.LayoutParams(-1, -1))
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.url.host !in setOf("compat.test", "other.test")) return null
                        val html = if (request.url.path == "/frame") """
                            <script>parent.postMessage({origin:location.origin,filled:typeof Promise.withResolvers==='function' && typeof AbortSignal.any==='function'},'*')</script>
                        """ else """
                            <script>
                              window.fixture={inline:typeof Promise.withResolvers==='function' && typeof AbortSignal.any==='function'};
                              window.addEventListener('message',event=>{const data=event.data;if(data && typeof data.filled==='boolean')fixture[data.origin]=data.filled});
                              if(fixture.inline){
                                const source=new AbortController();const combined=AbortSignal.any([source.signal]);source.abort('fixture-reason');
                                fixture.abort=combined.aborted && combined.reason==='fixture-reason';
                                const deferred=Promise.withResolvers();deferred.promise.then(value=>fixture.promise=value==='fixture-resolved');deferred.resolve('fixture-resolved');
                              }
                            </script>
                            <iframe src="https://compat.test/frame"></iframe>
                            <iframe src="https://other.test/frame"></iframe>
                            <iframe src="https://compat.test:8443/frame"></iframe>
                        """
                        return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                    }
                    override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) { stages += "started" }
                    override fun onPageFinished(view: WebView, url: String?) { stages += "finished"; loaded.countDown() }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) { stages += "error:${error.errorCode}" }
                }
                supported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (supported) {
                    // Test-only preparation forces missing APIs on every controlled fixture origin,
                    // even when a future provider already implements them. Registered scripts run in order.
                    reset = WebViewCompat.addDocumentStartJavaScript(view,
                        "delete Promise.withResolvers; delete AbortSignal.any;",
                        setOf(site.origin, "https://other.test", "https://compat.test:8443"))
                }
                compatibility = WebCompatibility.install(view, site)
                if (!supported) assertNull("Unsupported provider must not receive a late fallback", compatibility)
            }
            assertTrue("This acceptance device must support DOCUMENT_START_SCRIPT; update its provider if unavailable", supported)
            assertNotNull(compatibility)
            val attachDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((!view.isAttachedToWindow || view.width == 0) && System.nanoTime() < attachDeadline) Thread.sleep(20)
            instrumentation.runOnMainSync {
                assertTrue("Fixture must be attached and visible", view.isAttachedToWindow && view.width > 0 && view.windowVisibility == android.view.View.VISIBLE)
                view.loadUrl(site.entryUrl)
            }
            assertTrue("Controlled document and frames loaded: $stages", loaded.await(15, TimeUnit.SECONDS))
            val first = evaluate(instrumentation, view, "JSON.stringify(window.fixture)")
            val state = Json.parseToJsonElement(Json.parseToJsonElement(first).jsonPrimitive.content).jsonObject
            assertTrue("Both APIs must exist before the first inline script", state.getValue("inline").jsonPrimitive.boolean)
            assertTrue(state.getValue("abort").jsonPrimitive.boolean)
            assertTrue(state.getValue("promise").jsonPrimitive.boolean)
            assertTrue("Same-origin frame is covered", state.getValue(site.origin).jsonPrimitive.boolean)
            assertFalse("Other host must not receive the script", state.getValue("https://other.test").jsonPrimitive.boolean)
            assertFalse("Other port must not receive the script", state.getValue("https://compat.test:8443").jsonPrimitive.boolean)
            loaded = CountDownLatch(1)
            instrumentation.runOnMainSync { compatibility?.remove(); compatibility = null; view.loadUrl("https://compat.test/after-removal") }
            assertTrue("New document after removal loaded: $stages", loaded.await(15, TimeUnit.SECONDS))
            assertEquals("false", evaluate(instrumentation, view, "String(window.fixture.inline)" ).let { Json.parseToJsonElement(it).jsonPrimitive.content })
        } finally {
            instrumentation.runOnMainSync {
                compatibility?.remove(); reset?.remove()
                if (viewCreated) { (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() }
                host.finish()
            }
        }
    }

    @Test fun deferredInstallAfterCommitThenReloadAppliesDocumentStart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val site = Site(name = "Deferred compat fixture", entryUrl = "https://compat.test/")
        val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var view: WebView
        var viewCreated = false
        var loaded = CountDownLatch(1)
        var committed = CountDownLatch(1)
        var reset: ScriptHandler? = null
        var compatibility: ScriptHandler? = null
        var supported = false
        val stages = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            instrumentation.runOnMainSync {
                view = WebView(host)
                viewCreated = true
                view.settings.javaScriptEnabled = true
                host.addContentView(view, android.view.ViewGroup.LayoutParams(-1, -1))
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.url.host != "compat.test") return null
                        val html = """
                            <script>
                              window.fixture={inline:typeof Promise.withResolvers==='function' && typeof AbortSignal.any==='function'};
                            </script>
                        """.trimIndent()
                        return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                    }
                    override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) { stages += "started" }
                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        stages += "commit"
                        committed.countDown()
                    }
                    override fun onPageFinished(view: WebView, url: String?) { stages += "finished"; loaded.countDown() }
                }
                supported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (supported) {
                    reset = WebViewCompat.addDocumentStartJavaScript(view,
                        "delete Promise.withResolvers; delete AbortSignal.any;",
                        setOf(site.origin))
                }
            }
            assertTrue("This acceptance device must support DOCUMENT_START_SCRIPT", supported)
            val attachDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while ((!view.isAttachedToWindow || view.width == 0) && System.nanoTime() < attachDeadline) Thread.sleep(20)
            instrumentation.runOnMainSync {
                assertTrue(view.isAttachedToWindow && view.width > 0)
                view.loadUrl(site.entryUrl)
            }
            assertTrue("First document finished: $stages", loaded.await(15, TimeUnit.SECONDS))
            assertTrue("First commit visible: $stages", committed.await(5, TimeUnit.SECONDS))
            val before = evaluate(instrumentation, view, "JSON.stringify(window.fixture)")
            assertTrue("First document must lack polyfill when install is deferred", before.contains("\"inline\":false"))
            instrumentation.runOnMainSync {
                compatibility = WebCompatibility.install(view, site)
                assertNotNull(compatibility)
            }
            val probe = evaluate(instrumentation, view, WebCompatibility.API_PROBE)
            assertEquals("false", Json.parseToJsonElement(probe).jsonPrimitive.content)
            assertTrue(CompatReloadGate.shouldIssueReload(apisPresent = false, alreadyIssued = false))
            loaded = CountDownLatch(1)
            committed = CountDownLatch(1)
            instrumentation.runOnMainSync { view.reload() }
            assertTrue("Reload after deferred install finished: $stages", loaded.await(15, TimeUnit.SECONDS))
            val after = evaluate(instrumentation, view, "JSON.stringify(window.fixture)")
            assertTrue("Second navigation must run document-start compat: $after", after.contains("\"inline\":true"))
            assertFalse(CompatReloadGate.shouldIssueReload(apisPresent = true, alreadyIssued = true))
        } finally {
            instrumentation.runOnMainSync {
                compatibility?.remove(); reset?.remove()
                if (viewCreated) { (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() }
                host.finish()
            }
        }
    }

    private fun evaluate(instrumentation: android.app.Instrumentation, view: WebView, script: String): String {
        val done = CountDownLatch(1)
        val value = AtomicReference<String>()
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { value.set(it); done.countDown() } }
        assertTrue("JavaScript completion callback", done.await(5, TimeUnit.SECONDS))
        return value.get()
    }
}
