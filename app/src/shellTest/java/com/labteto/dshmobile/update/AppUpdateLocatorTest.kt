package com.labteto.dshmobile.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppUpdateLocatorTest {
    private val offer = AppUpdateOffer("9.9.9", 90909, "https://example.test/a.apk", "a.apk", -1L, null, null)

    @Test fun `a throwing primary source still falls back to the backup`() = runBlocking {
        val locator = AppUpdateLocator(listOf({ throw java.net.SocketTimeoutException("timeout") }, { offer }))
        assertEquals(offer, locator.latest())
    }

    @Test fun `primary offer wins and the backup is not consulted`() = runBlocking {
        var backupCalls = 0
        val locator = AppUpdateLocator(listOf({ offer }, { backupCalls++; null }))
        assertEquals(offer, locator.latest())
        assertEquals(0, backupCalls)
    }

    @Test fun `all sources failing yields no offer instead of throwing`() = runBlocking {
        val locator = AppUpdateLocator(listOf({ throw IllegalStateException("a") }, { throw java.io.IOException("b") }))
        assertNull(locator.latest())
    }

    @Test fun `cancellation is propagated and never swallowed`() {
        val locator = AppUpdateLocator(listOf({ throw CancellationException("cancelled") }, { offer }))
        try {
            runBlocking { locator.latest() }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }

    @Test fun `manifest fields of the wrong JSON type mean no offer, not a crash`() {
        assertNull(HostedAppUpdateSource.parseManifest("""{"versionName":{"x":1},"apkUrl":"https://e.test/a.apk"}"""))
        assertNull(HostedAppUpdateSource.parseManifest("""{"versionName":"1.0.0","apkUrl":["https://e.test/a.apk"]}"""))
        val parsed = HostedAppUpdateSource.parseManifest(
            """{"versionName":"1.0.0","apkUrl":"https://e.test/a.apk","apkName":{"n":1},"sha256":[],"releaseNotes":{}}""",
        )
        assertNotNull(parsed)
        assertEquals("a.apk", parsed!!.apkName)
        assertNull(parsed.sha256)
    }
}
