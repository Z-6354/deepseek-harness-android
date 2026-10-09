package com.labteto.dshmobile.browser

/** Monotonic, bounded and identifier-free diagnostics; instrumentation never owns readiness. */
enum class RuntimeStage(val wire: String) {
    Begin("beginLaunch"), Loading("loadUrl"), Started("documentStarted"), Committed("documentCommitted"),
    Interactive("pageReady"), Visual("visualComplete"), Restored("restoreWithoutReload"), WebLifecycle("webLifecycle")
}
data class RuntimeSample(val stage: String, val elapsedMs: Long, val browserInstance: Long, val documentGeneration: Long, val webElapsedMs: Double? = null)
class RuntimeDiagnostics(private val enabled: Boolean, private val now: () -> Long, private val sink: (RuntimeSample) -> Unit = {}) {
    private val started = now()
    private val samples = ArrayDeque<RuntimeSample>()
    @Synchronized fun mark(stage: RuntimeStage, lease: DocumentLease, webStage: String? = null, webElapsedMs: Double? = null) {
        if (!enabled) return
        val sample = RuntimeSample(webStage ?: stage.wire, (now() - started).coerceAtLeast(0), lease.browserInstance, lease.documentGeneration, webElapsedMs)
        if (samples.size == 128) samples.removeFirst()
        samples.addLast(sample)
        runCatching { sink(sample) }
    }
    @Synchronized fun snapshot() = samples.toList()
}

/** Per-document notification budget. A malformed or stale diagnostic never changes page state. */
class PageLifecycleBudget(private val now: () -> Long) {
    private var owner: DocumentLease? = null
    private var sequence = 0
    private var total = 0
    private var window = 0L
    private var count = 0
    fun accept(lease: DocumentLease, event: PageLifecycle): Boolean {
        val time = now()
        if (owner != lease) { owner = lease; sequence = 0; total = 0; window = time; count = 0 }
        if (time - window >= 1_000) { window = time; count = 0 }
        if (event.sequence <= sequence || total >= 64 || count >= 16) return false
        sequence = event.sequence; total++; count++; return true
    }
}
