package com.labteto.dshmobile.browser

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class SafeDownloadTest {
    private fun setup(): Pair<MockWebServer, SafeDownload> {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
        return server to SafeDownload(OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).followRedirects(false).followSslRedirects(false).build())
    }
    @Test fun `authenticated same origin redirect streams only file and transient cookie`() = runBlocking {
        val (server, runner) = setup()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/download"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("file"))
            val url = server.url("/start").toString()
            val site = Site(name = "Test", entryUrl = server.url("/").toString())
            val output = ByteArrayOutputStream()
            val paths = mutableListOf<String>()
            runner.transfer(site, url, { paths += it; "session=test-only" }, { true }, output)
            assertEquals("file", output.toString())
            assertEquals(2, paths.size)
            repeat(2) { assertEquals("session=test-only", server.takeRequest(5, TimeUnit.SECONDS)?.getHeader("Cookie")) }
        } finally { server.shutdown() }
    }
    @Test fun `cross origin redirect rejected before sending cookie or next request`() = runBlocking {
        val (server, runner) = setup()
        val other = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal")))
            val site = Site(name = "Test", entryUrl = server.url("/").toString())
            val failed = runCatching { runner.transfer(site, server.url("/start").toString(), { "secret-test" }, { true }, ByteArrayOutputStream()) }.exceptionOrNull()
            assertEquals("Cross-origin download refused", failed?.message)
            assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { server.shutdown(); other.shutdown() }
    }
    @Test fun `login HTML rejected and expired owner never sends request`() = runBlocking {
        val (server, runner) = setup()
        try {
            val site = Site(name = "Test", entryUrl = server.url("/").toString())
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("login"))
            val output = ByteArrayOutputStream()
            val failed = runCatching { runner.transfer(site, site.entryUrl, { null }, { true }, output) }.exceptionOrNull()
            assertEquals("The server returned a page, not a file", failed?.message)
            assertEquals(0, output.size())
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(runCatching { runner.transfer(site, site.entryUrl, { error("must not read cookie") }, { false }, output) }.isFailure)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { server.shutdown() }
    }
    @Test fun `mislabeled login HTML and oversized response are refused before output`() = runBlocking {
        val (server, runner) = setup()
        try {
            val site = Site(name = "Test", entryUrl = server.url("/").toString())
            val output = ByteArrayOutputStream()
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("<!doctype html><html>login</html>"))
            assertEquals("The server returned a login page", runCatching { runner.transfer(site, site.entryUrl, { null }, { true }, output) }.exceptionOrNull()?.message)
            assertEquals(0, output.size())
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("file").setHeader("Content-Length", SafeDownload.MAX_BYTES + 1))
            assertEquals("File exceeds 25 MiB", runCatching { runner.transfer(site, site.entryUrl, { null }, { true }, output) }.exceptionOrNull()?.message)
            assertEquals(0, output.size())
        } finally { server.shutdown() }
    }
    @Test fun `pause before asynchronous work cancels lease before cookie or call publication`() = runBlocking {
        val (server, runner) = setup()
        try {
            val site = Site(name = "Test", entryUrl = server.url("/").toString())
            val lease = runner.newLease()
            val output = ByteArrayOutputStream()
            runner.cancel()
            val failed = runCatching { runner.transfer(site, site.entryUrl, { error("Cookie must not be read") }, { true }, output, lease) }.exceptionOrNull()
            assertEquals("Download expired", failed?.message)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            assertEquals(0, output.size())
        } finally { server.shutdown() }
    }
    @Test fun `pause during cookie suspension prevents authenticated request and file writes`() = runBlocking {
        val (server, runner) = setup()
        try {
            val site = Site(name = "Test", entryUrl = server.url("/").toString())
            val lease = runner.newLease()
            val entered = CompletableDeferred<Unit>()
            val cookie = CompletableDeferred<String>()
            val output = ByteArrayOutputStream()
            val result = async { runCatching { runner.transfer(site, site.entryUrl, { entered.complete(Unit); cookie.await() }, { true }, output, lease) } }
            withTimeout(5000) { entered.await() }
            runner.cancel(); cookie.complete("must-not-be-sent")
            assertEquals("Download expired", withTimeout(5000) { result.await() }.exceptionOrNull()?.message)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            assertEquals(0, output.size())
        } finally { server.shutdown() }
    }
    @Test fun `pause between redirect legs remains canceled after first call released`() = runBlocking {
        val (server, runner) = setup()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/second"))
            val site = Site(name = "Test", entryUrl = server.url("/").toString())
            val lease = runner.newLease()
            val entered = CompletableDeferred<Unit>()
            val resumed = CompletableDeferred<Unit>()
            val output = ByteArrayOutputStream()
            var reads = 0
            val result = async { runCatching { runner.transfer(site, site.entryUrl, {
                reads++
                if (reads == 2) { entered.complete(Unit); resumed.await() }
                "test-cookie"
            }, { true }, output, lease) } }
            withTimeout(5000) { entered.await() }
            runner.cancel(); resumed.complete(Unit)
            assertEquals("Download expired", withTimeout(5000) { result.await() }.exceptionOrNull()?.message)
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            assertEquals(0, output.size())
        } finally { server.shutdown() }
    }
}
