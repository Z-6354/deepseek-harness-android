package com.labteto.dshmobile.browser

import okhttp3.Call

/** Cancellation belongs to the whole operation, including cookie waits and redirect gaps. */
class DownloadLease {
    private var canceled = false
    private var call: Call? = null
    @Synchronized fun cancel() { canceled = true; call?.cancel() }
    @Synchronized fun ensureActive(owns: () -> Boolean) { check(!canceled && owns()) { "Download expired" } }
    @Synchronized fun publish(next: Call, owns: () -> Boolean) {
        try { ensureActive(owns); call = next } catch (failure: Exception) { next.cancel(); throw failure }
    }
    @Synchronized fun release(active: Call) { active.cancel(); if (call === active) call = null }
    fun write(owns: () -> Boolean, action: () -> Unit) { ensureActive(owns); action() }
}
