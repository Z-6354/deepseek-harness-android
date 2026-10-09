package com.labteto.dshmobile.browser

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StaticAssetCacheTest {
    @Test fun largeImmutableAssetReplaysFromDiskWithoutHeadAndSurvivesRestart() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val directory = Files.createTempDirectory("static-assets").toFile()
        val site = Site(name = "Test", entryUrl = server.url("/").toString(), staticResourcePrefixes = listOf("/assets/", "/plugins/"))
        val url = server.url("/plugins/bundle.js?rev=123&modules=" + "a".repeat(3000)).toString()
        fun response() = MockResponse().setHeader("Content-Type", "text/javascript").setHeader("Cache-Control", "public, max-age=31536000, immutable")
        var cache = StaticAssetCache(directory, baseClient = client)
        try {
            val script = "a".repeat(6 * 1024 * 1024)
            server.enqueue(response().setBody(script))
            assertEquals(script, cache.load(site, url, emptyMap(), "session=fixture", { true })!!.stream.bufferedReader().use { it.readText() })
            assertEquals("GET", server.takeRequest().method); assertEquals(1, server.requestCount)
            cache.close(); cache = StaticAssetCache(directory, baseClient = client)
            assertEquals(script, cache.load(site, url, emptyMap(), "session=expired", { true })!!.stream.bufferedReader().use { it.readText() })
            assertEquals(1, server.requestCount)
            assertNull(cache.load(site, server.url("/api/private").toString(), emptyMap(), null, { true }))
            assertNull(cache.load(site, url, emptyMap(), null, { false }))
            assertNull(cache.load(site, "https://other.test/plugins/large.js", emptyMap(), "secret", { true }))
            assertEquals(1, server.requestCount)
            server.enqueue(response().setBody("new revision"))
            assertEquals("new revision", cache.load(site, url.replace("rev=123", "rev=456"), emptyMap(), "session=fixture", { true })!!.stream.bufferedReader().use { it.readText() })
            assertEquals("GET", server.takeRequest().method)
            assertEquals(2, server.requestCount)
        } finally { cache.close(); server.shutdown(); directory.deleteRecursively() }
    }
    @Test fun loginPagesPrivateResponsesAndCookieDependentScriptsNeverReplay() {
        fun response(type: String, cache: String, vary: String = "") = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://example.test/assets/a.js").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", type).header("Cache-Control", cache).apply { if (vary.isNotEmpty()) header("Vary", vary) }.build()
        assertFalse(StaticAssetCache.eligible(response("text/html", "public, immutable")))
        assertFalse(StaticAssetCache.eligible(response("text/javascript", "private, immutable")))
        assertFalse(StaticAssetCache.eligible(response("text/javascript", "public, immutable", "Cookie")))
        assertFalse(StaticAssetCache.eligible(response("text/javascript", "public, immutable").newBuilder().header("Set-Cookie", "secret").build()))
        assertTrue(StaticAssetCache.eligible(response("text/javascript", "public, max-age=31536000, immutable", "Accept-Encoding")))
        assertTrue(StaticAssetCache.eligible(response("text/javascript", "public, max-age=31536000, immutable", "Accept-Encoding")
            .newBuilder().header("Content-Length", "2").body("ok".toResponseBody("text/javascript".toMediaType())).build()))
        assertFalse(StaticAssetCache.eligible(response("text/javascript", "public, max-age=31536000, immutable", "Accept-Encoding")
            .newBuilder().header("Content-Length", (StaticAssetCache.MAX_BYTES + 1).toString()).build()))
        assertTrue(StaticAssetCache.cacheableUrl("https://example.test/plugins/bundle.js?rev=1".toHttpUrl()))
        assertTrue(StaticAssetCache.cacheableUrl("https://example.test/assets/app.css".toHttpUrl()))
        assertTrue(StaticAssetCache.vitePluginBundle("https://example.test/plugins/??bundle.js&rev=a87e748c7ff1".toHttpUrl()))
        assertTrue(StaticAssetCache.cacheableUrl("https://example.test/plugins/??bundle.js&rev=a87e748c7ff1".toHttpUrl()))
        assertTrue(StaticAssetCache.cacheableUrl("https://example.test/plugins/??@scope/a/client.js,@scope/b/client.js&rev=a87e748c7ff1".toHttpUrl()))
        assertFalse(StaticAssetCache.cacheableUrl("https://example.test/plugins/?rev=1&modules=a".toHttpUrl()))
        assertFalse(StaticAssetCache.cacheableUrl("https://example.test/plugins/??bundle.js".toHttpUrl()))
        assertFalse(StaticAssetCache.cacheableUrl("https://example.test/plugins/hmr".toHttpUrl()))
        assertTrue(StaticAssetCache.eligible(response("text/javascript", "").newBuilder()
            .request(okhttp3.Request.Builder().url("https://example.test/assets/index-DjTxlw_T.js").build())
            .removeHeader("Cache-Control").build()))
        assertTrue(StaticAssetCache.eligible(response("text/javascript", "").newBuilder()
            .request(okhttp3.Request.Builder().url("https://example.test/plugins/??a.js,b.js&rev=deadbeef").build())
            .removeHeader("Cache-Control").build()))
        assertFalse(StaticAssetCache.eligible(response("text/javascript", "").newBuilder()
            .request(okhttp3.Request.Builder().url("https://example.test/assets/app.js").build())
            .removeHeader("Cache-Control").build()))
    }

    @Test fun chunkedBodyWithoutContentLengthIsBoundedAndNeverPublished() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val counted = java.util.concurrent.atomic.AtomicLong()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body ?: return@addInterceptor response
                val counting = object : okio.ForwardingSource(body.source()) {
                    override fun read(sink: okio.Buffer, byteCount: Long): Long =
                        super.read(sink, byteCount).also { if (it > 0) counted.addAndGet(it) }
                }
                response.newBuilder().body(object : okhttp3.ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = counting.buffer()
                }).build()
            }.build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val directory = Files.createTempDirectory("static-assets-chunked").toFile()
        val site = Site(name = "Test", entryUrl = server.url("/").toString(), staticResourcePrefixes = listOf("/assets/"))
        val url = server.url("/assets/huge-AbCdEf123.js").toString()
        val cache = StaticAssetCache(directory, baseClient = client)
        try {
            // Chunked => no Content-Length, so eligible() cannot reject it from headers alone.
            // Far larger than the cap, throttled so the client's read position is observable: an unbounded
            // body.bytes() keeps pulling until the server finishes, a bounded read stops just past 32 MiB.
            val total = StaticAssetCache.MAX_BYTES * 3
            server.enqueue(MockResponse().setHeader("Content-Type", "text/javascript")
                .setHeader("Cache-Control", "public, max-age=31536000, immutable")
                .setChunkedBody(okio.Buffer().write(ByteArray(total.toInt())), 64 * 1024))
            val result = cache.intercept(site, url, emptyMap(), null, { true })
            assertTrue("oversize chunked asset must fail, was $result", result is StaticAssetCache.Intercept.Failed)
            assertEquals(502, (result as StaticAssetCache.Intercept.Failed).status)
            // Bounded read stops just past the cap; the old body.bytes() pulled all 96 MiB before checking.
            assertTrue("client read ${counted.get()} bytes; must stop near the ${StaticAssetCache.MAX_BYTES} cap",
                counted.get() in 1..(StaticAssetCache.MAX_BYTES * 3 / 2))
            val published = File(directory, "store").listFiles().orEmpty().filter { it.isFile }
            assertTrue("nothing may be published: ${published.map { it.name }}", published.isEmpty())
        } finally { cache.close(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun vitePluginBundleWithoutCacheControlReplaysFromDisk() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val directory = Files.createTempDirectory("static-assets-vite").toFile()
        val site = Site(name = "Test", entryUrl = server.url("/").toString(), staticResourcePrefixes = listOf("/assets/", "/plugins/"))
        val url = server.url("/plugins/").newBuilder().encodedQuery("?@scope/a/client.js,@scope/b/client.js&rev=a87e748c7ff1").build().toString()
        val body = "export default 1;" + "x".repeat(64 * 1024)
        var cache = StaticAssetCache(directory, baseClient = client)
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/javascript; charset=utf-8").setHeader("Vary", "Accept-Encoding").setBody(body))
            assertEquals(body, cache.load(site, url, emptyMap(), null, { true })!!.stream.bufferedReader().use { it.readText() })
            assertEquals(1, server.requestCount)
            cache.close(); cache = StaticAssetCache(directory, baseClient = client)
            assertEquals(body, cache.load(site, url, emptyMap(), null, { true })!!.stream.bufferedReader().use { it.readText() })
            assertEquals(1, server.requestCount)
        } finally { cache.close(); server.shutdown(); directory.deleteRecursively() }
    }
    @Test fun diskCacheIsDeletedInsteadOfLeavingCookieRecords() {
        val cacheDir = Files.createTempDirectory("asset-root").toFile()
        try {
            val cookie = java.io.File(StaticAssetCache.root(cacheDir), "owner/journal").apply {
                check(parentFile?.mkdirs() == true || parentFile?.isDirectory == true)
                writeText("Cookie: session=secret")
            }
            assertTrue(cookie.isFile)
            StaticAssetCache.clear(cacheDir)
            assertFalse(cookie.exists())
            assertFalse(StaticAssetCache.root(cacheDir).exists())
        } finally { cacheDir.deleteRecursively() }
    }

    @Test fun cacheOwnedMissReturnsFailureInsteadOfHandingBackToWebView() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val directory = Files.createTempDirectory("static-assets-fail").toFile()
        val site = Site(name = "Test", entryUrl = server.url("/").toString(), staticResourcePrefixes = listOf("/assets/", "/plugins/"))
        val url = server.url("/assets/index-DjTxlw_T.js").toString()
        val cache = StaticAssetCache(directory, baseClient = client)
        try {
            server.enqueue(MockResponse().setResponseCode(500).setBody("no"))
            val owned = cache.intercept(site, url, emptyMap(), null, { true })
            assertTrue(owned is StaticAssetCache.Intercept.Failed)
            assertEquals(1, server.requestCount)
            val skipped = cache.intercept(site, server.url("/api/private").toString(), emptyMap(), null, { true })
            assertEquals(StaticAssetCache.Intercept.Skip, skipped)
            assertEquals(1, server.requestCount)
        } finally { cache.close(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun concurrentMissesShareOneNetworkDownload() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val directory = Files.createTempDirectory("static-assets-flight").toFile()
        val site = Site(name = "Test", entryUrl = server.url("/").toString(), staticResourcePrefixes = listOf("/assets/", "/plugins/"))
        val url = server.url("/plugins/").newBuilder().encodedQuery("?a.js&rev=shared1").build().toString()
        val body = "export default 1;" + "z".repeat(4096)
        val cache = StaticAssetCache(directory, baseClient = client)
        try {
            server.enqueue(MockResponse().setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setHeader("Content-Type", "text/javascript").setHeader("Vary", "Accept-Encoding").setBody(body))
            val first = java.util.concurrent.CompletableFuture.supplyAsync { cache.load(site, url, emptyMap(), null, { true })!!.stream.bufferedReader().use { it.readText() } }
            val second = java.util.concurrent.CompletableFuture.supplyAsync { cache.load(site, url, emptyMap(), null, { true })!!.stream.bufferedReader().use { it.readText() } }
            assertEquals(body, first.get())
            assertEquals(body, second.get())
            assertEquals(1, server.requestCount)
        } finally { cache.close(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun retiredConsumerDoesNotCancelSameSiteProducerOrTriggerFallbackRequest() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val directory = Files.createTempDirectory("static-assets-detached-consumer").toFile()
        val site = Site(name = "Test", entryUrl = server.url("/").toString(), staticResourcePrefixes = listOf("/assets/", "/plugins/"))
        val url = server.url("/plugins/").newBuilder().encodedQuery("?a.js&rev=lease1").build().toString()
        val cache = StaticAssetCache(directory, baseClient = client)
        val consumerActive = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            server.enqueue(MockResponse().setHeadersDelay(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setHeader("Content-Type", "text/javascript").setHeader("Vary", "Accept-Encoding").setBody("export default 'cached';"))
            val request = java.util.concurrent.CompletableFuture.supplyAsync {
                cache.intercept(site, url, emptyMap(), null, producerAllowed = { true }, consumerAllowed = { consumerActive.get() })
            }
            server.takeRequest()
            consumerActive.set(false)
            assertTrue(request.get() is StaticAssetCache.Intercept.Failed)
            assertEquals(1, server.requestCount)
            consumerActive.set(true)
            val laterConsumer = cache.intercept(site, url, emptyMap(), null, producerAllowed = { true }, consumerAllowed = { true })
            assertTrue(laterConsumer is StaticAssetCache.Intercept.Ready)
            assertEquals(1, server.requestCount)
        } finally { cache.close(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun producerRetirementAfterAdoptionFailsLocallyAndDoesNotPublish() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val directory = Files.createTempDirectory("static-assets-retired-producer").toFile()
        val site = Site(name = "Test", entryUrl = server.url("/").toString(), staticResourcePrefixes = listOf("/assets/", "/plugins/"))
        val url = server.url("/plugins/").newBuilder().encodedQuery("?a.js&rev=producer1").build().toString()
        val cache = StaticAssetCache(directory, baseClient = client)
        val producerActive = java.util.concurrent.atomic.AtomicBoolean(true)
        try {
            server.enqueue(MockResponse().setHeadersDelay(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setHeader("Content-Type", "text/javascript").setHeader("Vary", "Accept-Encoding").setBody("export default 'must-not-publish';"))
            val request = java.util.concurrent.CompletableFuture.supplyAsync {
                cache.intercept(site, url, emptyMap(), null, producerAllowed = { producerActive.get() }, consumerAllowed = { true })
            }
            server.takeRequest()
            producerActive.set(false)
            assertTrue(request.get() is StaticAssetCache.Intercept.Failed)
            assertEquals(1, server.requestCount)
            assertFalse(java.io.File(directory, "store").listFiles()?.any { it.name.endsWith(".meta") || it.name.endsWith(".bin") } == true)
        } finally { cache.close(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun localHitStreamsFromDiskWithContentLength() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).cache(null).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val directory = Files.createTempDirectory("static-assets-stream").toFile()
        val site = Site(name = "Test", entryUrl = server.url("/").toString(), staticResourcePrefixes = listOf("/assets/", "/plugins/"))
        val url = server.url("/plugins/").newBuilder().encodedQuery("?a.js,b.js&rev=stream1").build().toString()
        val body = "export default 1;" + "s".repeat(256 * 1024)
        var cache = StaticAssetCache(directory, baseClient = client)
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/javascript").setHeader("Vary", "Accept-Encoding").setBody(body))
            assertEquals(body, cache.load(site, url, emptyMap(), null, { true })!!.stream.bufferedReader().use { it.readText() })
            cache.close()
            cache = StaticAssetCache(directory, baseClient = client)
            val hit = cache.load(site, url, emptyMap(), null, { true })
            assertNotNull(hit)
            assertEquals(body.length, hit!!.size)
            assertEquals(body.length.toString(), hit.headers["Content-Length"])
            assertEquals(body, hit.stream.bufferedReader().use { it.readText() })
            assertEquals(1, server.requestCount)
        } finally { cache.close(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun localStoreSurvivesNewClientWithoutOkHttpCache() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).cache(null).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val directory = Files.createTempDirectory("static-assets-local").toFile()
        val site = Site(name = "Test", entryUrl = server.url("/").toString(), staticResourcePrefixes = listOf("/assets/", "/plugins/"))
        val url = server.url("/plugins/").newBuilder().encodedQuery("?a.js,b.js&rev=deadbeef").build().toString()
        val body = "export default 1;" + "y".repeat(8 * 1024)
        var cache = StaticAssetCache(directory, baseClient = client)
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/javascript").setHeader("Vary", "Accept-Encoding").setBody(body))
            assertEquals(body, cache.load(site, url, emptyMap(), null, { true })!!.stream.bufferedReader().use { it.readText() })
            assertEquals(1, server.requestCount)
            cache.close()
            cache = StaticAssetCache(directory, baseClient = client)
            assertEquals(body, cache.load(site, url, emptyMap(), null, { true })!!.stream.bufferedReader().use { it.readText() })
            assertEquals(1, server.requestCount)
            assertTrue(java.io.File(directory, "store").listFiles()?.any { it.name.endsWith(".bin") } == true)
        } finally { cache.close(); server.shutdown(); directory.deleteRecursively() }
    }

    @Test fun interceptedBodyIsBufferedSoCallTimeoutDoesNotTrackWebViewReads() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .callTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val directory = Files.createTempDirectory("static-assets-buffer").toFile()
        val site = Site(name = "Test", entryUrl = server.url("/").toString(), staticResourcePrefixes = listOf("/assets/", "/plugins/"))
        val url = server.url("/plugins/").newBuilder().encodedQuery("?a.js,b.js&rev=deadbeef").build().toString()
        val body = "export default 1;" + "x".repeat(32 * 1024)
        val cache = StaticAssetCache(directory, baseClient = client)
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/javascript").setHeader("Vary", "Accept-Encoding").setBody(body))
            val asset = cache.load(site, url, emptyMap(), null, { true })
            assertNotNull(asset)
            Thread.sleep(1500)
            assertEquals(body, asset!!.stream.bufferedReader().use { it.readText() })
        } finally { cache.close(); server.shutdown(); directory.deleteRecursively() }
    }
}
