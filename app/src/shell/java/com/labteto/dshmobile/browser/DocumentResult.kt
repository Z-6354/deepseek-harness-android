package com.labteto.dshmobile.browser

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Bounded WebView result wait; native task cancellation retires a missing JS callback. */
internal suspend fun awaitDocumentResult(owns: () -> Boolean, timeoutMs: Long = 5_000, submit: ((String?) -> Unit) -> Unit): String? =
    withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            if (!owns()) { continuation.resume(null); return@suspendCancellableCoroutine }
            submit { value -> if (continuation.isActive) continuation.resume(if (owns()) value else null) }
        }
    }
