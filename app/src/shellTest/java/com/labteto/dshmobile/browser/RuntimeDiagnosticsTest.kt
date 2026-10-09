package com.labteto.dshmobile.browser

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeDiagnosticsTest {
    private fun payload(stage: String = "historyReady", sequence: Int = 1) = buildJsonObject { put("schemaVersion", 1); put("stage", stage); put("webElapsedMs", 12.5); put("sequence", sequence) }
    @Test fun `lifecycle is bounded numeric identifier free and requires committed exact document`() {
        assertNotNull(BridgeProtocol.lifecycle(payload()))
        assertNull(BridgeProtocol.lifecycle(payload("unknown")))
        assertNull(BridgeProtocol.lifecycle(payload(sequence = 0)))
        assertNull(BridgeProtocol.lifecycle(buildJsonObject { payload().forEach { (k, v) -> put(k, v) }; put("sessionId", "never") }))
        val site = Site(name = "fixture", entryUrl = "https://fixture.test/")
        assertFalse(BridgeProtocol.allowed(site, site.origin, true, null, 1, 1, "pageLifecycle"))
        assertTrue(BridgeProtocol.allowed(site, site.origin, true, site.entryUrl, 1, 1, "pageLifecycle"))
        assertFalse(BridgeProtocol.allowed(site, site.origin, false, site.entryUrl, 1, 1, "pageLifecycle"))
        assertFalse(BridgeProtocol.allowed(site, site.origin, true, site.entryUrl, 2, 1, "pageLifecycle"))
    }
    @Test fun `diagnostic rate replay and count budget resets only on a new lease`() {
        var time = 1L
        val budget = PageLifecycleBudget { time }
        val owner = DocumentLease(1, 1)
        for (i in 1..16) assertTrue(budget.accept(owner, PageLifecycle("bodyPaint", 1.0, i)))
        assertFalse(budget.accept(owner, PageLifecycle("bodyPaint", 1.0, 17)))
        time += 1000
        assertFalse(budget.accept(owner, PageLifecycle("bodyPaint", 1.0, 16)))
        assertTrue(budget.accept(owner, PageLifecycle("bodyPaint", 1.0, 17)))
        assertTrue(budget.accept(DocumentLease(2, 1), PageLifecycle("bodyPaint", 1.0, 1)))
    }
    @Test fun `diagnostic ring bounded disabled empty and failing sink cannot stop pipeline`() {
        var now = 10L
        val debug = RuntimeDiagnostics(true, { now }) { error("diagnostic transport unavailable") }
        repeat(200) { now++; debug.mark(RuntimeStage.Started, DocumentLease(1, 1)) }
        assertEquals(128, debug.snapshot().size)
        assertTrue(debug.snapshot().all { it.elapsedMs > 0 })
        val release = RuntimeDiagnostics(false, { now })
        release.mark(RuntimeStage.Visual, DocumentLease(1, 1)); assertTrue(release.snapshot().isEmpty())
    }
}
