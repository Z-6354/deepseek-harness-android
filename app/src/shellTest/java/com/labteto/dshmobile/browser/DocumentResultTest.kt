package com.labteto.dshmobile.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentResultTest {
    @Test fun `retiring native task ends wait even when javascript never replies`() = runTest {
        var ended = false
        val job = launch { try { awaitDocumentResult({ true }) { } } finally { ended = true } }
        runCurrent(); assertFalse(ended)
        job.cancel(); runCurrent()
        assertTrue(ended); assertTrue(job.isCancelled)
    }
    @Test fun `missing javascript callback has bounded timeout and stale result is ignored`() = runTest {
        var done: ((String?) -> Unit)? = null
        var value: String? = "pending"
        val job = launch { value = awaitDocumentResult({ true }, 10) { done = it } }
        runCurrent(); advanceTimeBy(11); runCurrent()
        assertTrue(job.isCompleted); assertNull(value)
        done?.invoke("late"); assertNull(value)
    }
}
