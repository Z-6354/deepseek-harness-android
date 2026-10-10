package com.labteto.dshmobile.browser

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Runs blocking work (Keystore, SharedPreferences.commit) one job at a time off the UI thread and hands
 * each result back through [deliver], which the owner binds to the UI thread.
 *
 * Jobs run in submission order, so a `save` queued before a `clear` can never land after it. Callers
 * decide at delivery time whether the result is still wanted (document lease, activity state); this
 * class never inspects it.
 */
internal class SerialIo(name: String, private val deliver: (Runnable) -> Unit) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, name).apply { isDaemon = true }
    }

    fun <T> submit(work: () -> T, done: (Result<T>) -> Unit) {
        try {
            executor.execute {
                val result = runCatching(work)
                deliver(Runnable { done(result) })
            }
        } catch (rejected: RejectedExecutionException) {
            deliver(Runnable { done(Result.failure(rejected)) })
        }
    }

    /** Lets queued jobs finish (a pending credential clear must not be dropped), then stops the thread. */
    fun close() { executor.shutdown() }
}
