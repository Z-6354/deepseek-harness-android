package com.labteto.dshmobile.browser

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BrowserPolicyTest {
    private val site = Site("test", "Test", "https://example.test/base/", "revision")
    @Test fun `origin uses effective port and rejects credentials or downgrade`() {
        assertEquals("https://example.test", NavigationPolicy.origin("https://EXAMPLE.test:443/a"))
        assertEquals(Navigation.INTERNAL, NavigationPolicy.decide(site, "https://example.test:443/other?q=1#x"))
        listOf("http://example.test/", "javascript:alert(1)", "intent://example.test/", "https://user:pass@example.test/", "https://example.test\\@evil.test/").forEach {
            assertEquals(it, Navigation.BLOCKED, NavigationPolicy.decide(site, it))
        }
        listOf("https://example.test:444/", "https://example.test.evil.test/", "https://other.test/").forEach {
            assertEquals(it, Navigation.EXTERNAL, NavigationPolicy.decide(site, it))
        }
        assertNull(NavigationPolicy.normalizedEntry("https://example.test/?token=secret"))
        assertNull(NavigationPolicy.normalizedEntry("https://example.test/#secret"))
        assertEquals("https://[::1]:444/", NavigationPolicy.normalizedEntry("https://[::1]:444"))
    }
    @Test fun `owner mismatch and pending cleanup never reuse old environment`() {
        assertEquals(EnvironmentDecision.REUSE, EnvironmentPolicy.decide("a", "a", false, true, false))
        assertEquals(EnvironmentDecision.UNSUPPORTED, EnvironmentPolicy.decide("a", "b", false, true, false))
        assertEquals(EnvironmentDecision.RESTART, EnvironmentPolicy.decide("a", "b", false, true, true))
        assertEquals(EnvironmentDecision.CLEAN, EnvironmentPolicy.decide("a", "b", true, false, true))
        assertEquals(EnvironmentDecision.RESTART, EnvironmentPolicy.decide("a", "a", true, true, true))
    }
    @Test fun `migration copies only HTTPS metadata without interpreting secrets`() {
        val input = """[{"id":"one","name":"saved","host":"example.test","port":443,"useTls":true,"password":"do-not-copy","cookie":"do-not-copy"},{"id":"http","host":"example.test","port":80,"useTls":false},{"id":"bad","host":"example.test/path","port":443,"useTls":true},{"id":"relay","host":"example.test","port":443,"useTls":true,"relayPin":"legacy"}]"""
        val result = LegacySiteMigration.extract(input)
        assertEquals(1, result.size)
        assertEquals("one", result.single().id)
        assertEquals("https://example.test/", result.single().entryUrl)
        assertFalse(result.toString().contains("do-not-copy"))
        assertTrue(LegacySiteMigration.extract("malformed").isEmpty())
    }
    @Test fun `page readiness accepts only empty payload`() {
        assertNotNull(BridgeProtocol.parse("""{"version":1,"id":"ready","type":"pageReady","payload":{}}"""))
        assertNull(BridgeProtocol.parse("""{"version":1,"id":"ready","type":"pageReady","payload":{"url":"https://evil.test"}}"""))
    }
    @Test fun `bridge requires main frame exact origin committed page and generation`() {
        assertTrue(BridgeProtocol.allowed(site, "https://example.test", true, site.entryUrl, 5, 5))
        assertFalse(BridgeProtocol.allowed(site, site.origin, false, site.entryUrl, 5, 5))
        assertFalse(BridgeProtocol.allowed(site, "https://example.test:444", true, site.entryUrl, 5, 5))
        assertFalse(BridgeProtocol.allowed(site, site.origin, true, site.entryUrl, 6, 5))
        assertFalse(BridgeProtocol.allowed(site, site.origin, true, null, 5, 5))
        assertFalse(BridgeProtocol.allowed(site, site.origin, true, "https://other.test/", 5, 5))
        assertTrue(BridgeProtocol.allowed(site, site.origin, true, null, 5, 5, "pageReady"))
        assertFalse(BridgeProtocol.allowed(site, site.origin, true, null, 5, 5, "capabilities"))
        assertFalse(BridgeProtocol.allowed(site, site.origin, false, null, 5, 5, "pageReady"))
    }
    @Test fun `bridge rejects malformed unsupported oversized and cross origin payloads`() {
        assertNotNull(BridgeProtocol.parse("""{"version":1,"id":"test","type":"capabilities","payload":{}}"""))
        assertNull(BridgeProtocol.parse("""{"version":2,"id":"test","type":"fetch","payload":{}}"""))
        assertNull(BridgeProtocol.parse("""{"version":1,"id":12,"type":"capabilities","payload":{}}"""))
        assertNull(BridgeProtocol.parse("x".repeat(16385)))
        val basic = buildJsonObject { put("title", "Done"); put("body", "Finished") }
        assertEquals(site.entryUrl, BridgeProtocol.notification(site, basic)?.targetUrl)
        assertNull(BridgeProtocol.notification(site, JsonObject(basic + ("targetUrl" to JsonPrimitive("https://evil.test/")))))
        assertNull(BridgeProtocol.notification(site, JsonObject(basic + ("title" to JsonPrimitive("x".repeat(121))))))
    }
    @Test fun `picker accepts content provider references only`() {
        assertTrue(FileSelectionPolicy.allowedUri("content://com.android.providers.media.documents/document/1"))
        listOf("file:///sdcard/private", "https://example.test/file", "content:///no-provider", "content://provider/\nfile", "data:text/plain,test").forEach { assertFalse(it, FileSelectionPolicy.allowedUri(it)) }
        assertFalse(FileSelectionPolicy.allowedProvider(1000, 1000))
        assertFalse(FileSelectionPolicy.allowedProvider(null, 1000))
        assertTrue(FileSelectionPolicy.allowedProvider(2000, 1000))
    }
    @Test fun `background live page permits capabilities and notification display but no permission UI`() {
        assertFalse(BridgeProtocol.requiresForeground("capabilities"))
        assertFalse(BridgeProtocol.requiresForeground("showNotification"))
        assertTrue(BridgeProtocol.requiresForeground("requestNotificationPermission"))
        assertTrue(BridgeProtocol.requiresForeground("readCredential"))
        assertTrue(BridgeProtocol.requiresForeground("saveCredential"))
    }
    @Test fun `bridge reply script roundtrips json including quotes unicode and rejects unsafe payloads`() {
        fun decode(script: String): String {
            val encoded = Regex("atob\\('([A-Za-z0-9+/=]+)'\\)").find(script)?.groupValues?.get(1)
                ?: error("missing atob payload")
            return String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }
        val capabilities = BridgeProtocol.reply("login-1", buildJsonObject {
            put("credentialStorage", true)
            put("bridgeVersion", 1)
        })
        assertEquals(capabilities, decode(BridgeReplyDelivery.scriptForReply(capabilities)!!))
        val password = BridgeProtocol.reply("cred", buildJsonObject {
            put("saved", true)
            put("password", "p\"ass'\\n\u4e2d\uD83D\uDE00")
        })
        assertEquals(password, decode(BridgeReplyDelivery.scriptForReply(password)!!))
        val script = BridgeReplyDelivery.scriptForReply(capabilities)!!
        assertTrue(script.contains("window.HanApp"))
        assertTrue(script.contains("h.onmessage"))
        assertNull(BridgeReplyDelivery.scriptForReply(""))
        assertNull(BridgeReplyDelivery.scriptForReply("x".repeat(BridgeProtocol.MAX_MESSAGE + 1)))
        assertNull(BridgeReplyDelivery.scriptForReply("{\"ok\":true}\u0000"))
        val source = java.io.File("src/shell/java/com/labteto/dshmobile/MainActivity.kt").readText()
        assertFalse(source.contains("proxy.postMessage"))
        assertTrue(source.contains("BridgeReplyDelivery.deliver"))
    }
    @Test fun `credential payload cannot select an origin or accept oversized nonstring passwords`() {
        assertNotNull(BridgeProtocol.parse("""{"version":1,"id":"credential","type":"readCredential","payload":{}}"""))
        assertNotNull(BridgeProtocol.parse("""{"version":1,"id":"credential","type":"saveCredential","payload":{"password":"example"}}"""))
        fun password(value: JsonElement) = buildJsonObject { put("password", value) }
        assertEquals("example", BridgeProtocol.credentialPassword(password(JsonPrimitive("example"))))
        assertEquals(4096, BridgeProtocol.credentialPassword(password(JsonPrimitive("x".repeat(4096))))?.length)
        assertNull(BridgeProtocol.credentialPassword(password(JsonPrimitive("x".repeat(4097)))))
        assertNull(BridgeProtocol.credentialPassword(password(JsonPrimitive(123))))
        assertNull(BridgeProtocol.credentialPassword(password(JsonPrimitive(""))))
        assertNull(BridgeProtocol.credentialPassword(buildJsonObject { put("password", "example"); put("origin", "https://other.test") }))
    }
    @Test fun `notification pending intents stay distinct per tag`() {
        val owner = "id|rev|https://example.test/"
        assertNotEquals(WebsiteNotification.requestCode(owner, "one"), WebsiteNotification.requestCode(owner, "two"))
        assertEquals(WebsiteNotification.requestCode(owner, null), WebsiteNotification.requestCode(owner, null))
        assertEquals(WebsiteNotification.tapUri(owner, "one"), WebsiteNotification.tapUri(owner, "one"))
        assertNotEquals(WebsiteNotification.tapUri(owner, "one"), WebsiteNotification.tapUri(owner, "two"))
    }
    @Test fun `shell never shows an extra English notification confirmation before the system prompt`() {
        val source = java.io.File("src/shell/java/com/labteto/dshmobile/MainActivity.kt").readText()
        assertFalse(source.contains("to request notifications"))
        assertFalse(source.contains("shouldShowRequestPermissionRationale"))
        assertTrue(source.contains("permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)"))
        assertTrue(source.contains("关闭应用"))
        assertFalse(source.contains("Close app"))
        assertTrue(source.contains("无法连接到网站"))
        assertTrue(source.contains("armLaunchDeadline"))
        assertTrue(source.contains("LaunchCoverState"))
        assertFalse(source.contains("slowLaunch"))
        assertFalse(source.contains("20_000"))
        assertTrue(source.contains("repository.migrated"))
        assertTrue(source.contains("lastDocument"))
        val cache = java.io.File("src/shell/java/com/labteto/dshmobile/browser/StaticAssetCache.kt").readText()
        assertFalse(cache.contains(".head()"))
        assertFalse(cache.contains("Request.HEAD"))
        assertTrue(cache.contains("Intercept.Failed"))
        val session = java.io.File("src/shell/java/com/labteto/dshmobile/browser/BrowserSession.kt").readText()
        assertTrue(session.contains("startUpWebView"))
        assertFalse(session.contains("WebView(this)"))
        assertFalse(session.contains("WebView(app)"))
        assertFalse(session.contains("new WebView"))
    }
}
