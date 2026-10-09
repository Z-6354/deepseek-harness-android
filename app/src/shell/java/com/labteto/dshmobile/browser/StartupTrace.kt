package com.labteto.dshmobile.browser

import android.os.SystemClock
import java.util.ArrayDeque

/** Separate monotonic clocks for process startup, activity launch and each first-document pipeline. */
object StartupTrace {
    private data class Domain(var startedAt: Long, val events: ArrayDeque<Pair<String, Long>> = ArrayDeque())
    private val domains = linkedMapOf<String, Domain>()
    @Synchronized fun mark(domain: String, event: String, now: Long = SystemClock.elapsedRealtime()) {
        require(domain.matches(Regex("[a-zA-Z]{1,24}")) && event.matches(Regex("[a-zA-Z0-9]{1,32}")))
        val clock = domains.getOrPut(domain) { Domain(now) }
        val elapsed = (now - clock.startedAt).coerceAtLeast(0)
        if (clock.events.size == 128) clock.events.removeFirst()
        clock.events.addLast(event to elapsed)
        if (com.labteto.dshmobile.BuildConfig.DEBUG) android.util.Log.i("LaunchTrace", "domain=$domain event=$event elapsedMs=$elapsed")
    }
    @Synchronized fun snapshot(domain: String): List<Pair<String, Long>> = domains[domain]?.events?.toList().orEmpty()
}
