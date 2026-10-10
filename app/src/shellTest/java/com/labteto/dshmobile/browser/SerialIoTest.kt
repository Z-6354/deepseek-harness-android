package com.labteto.dshmobile.browser

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SerialIoTest {
    /** A stand-in for the UI thread: results are only visible once the "main loop" drains them. */
    private val mainQueue = LinkedBlockingQueue<Runnable>()
    private val io = SerialIo("serial-io-test") { mainQueue.put(it) }

    private fun drain(count: Int) = repeat(count) {
        (mainQueue.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("no result was delivered")).run()
    }

    @Test fun workRunsOffTheCallerThreadAndResultsReturnOnTheDeliveryThread() {
        val caller = Thread.currentThread()
        var workThread: Thread? = null
        var doneThread: Thread? = null
        io.submit({ workThread = Thread.currentThread(); 7 }) { doneThread = Thread.currentThread(); assertEquals(7, it.getOrNull()) }
        drain(1)
        assertNotSame(caller, workThread)
        assertSame("delivery must run where the owner drains it, never on the worker", caller, doneThread)
        io.close()
    }

    @Test fun jobsRunInSubmissionOrderSoASaveNeverLandsAfterALaterClear() {
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val gate = CountDownLatch(1)
        io.submit({ gate.await(2, TimeUnit.SECONDS); log += "save" }) { }
        io.submit({ log += "clear" }) { }
        gate.countDown()
        drain(2)
        assertEquals(listOf("save", "clear"), log.toList())
        io.close()
    }

    @Test fun aThrowingJobIsReportedAsAFailureAndDoesNotKillTheQueue() {
        var first: Result<Int>? = null
        var second: Result<Int>? = null
        io.submit<Int>({ error("keystore unavailable") }) { first = it }
        io.submit({ 1 }) { second = it }
        drain(2)
        assertTrue(first!!.isFailure)
        assertEquals(1, second!!.getOrNull())
        io.close()
    }

    @Test fun closeLetsAlreadyQueuedJobsFinishButRejectsNewOnes() {
        var ran = false
        val gate = CountDownLatch(1)
        io.submit({ gate.await(2, TimeUnit.SECONDS); ran = true }) { }
        io.close()
        var rejected: Result<Int>? = null
        io.submit({ 1 }) { rejected = it }
        gate.countDown()
        drain(2)
        assertTrue("a queued clear must not be dropped by close()", ran)
        assertTrue(rejected!!.isFailure)
    }
}
