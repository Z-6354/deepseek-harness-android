package com.labteto.dshmobile.browser

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NativeReadRegistryTest {
    @Test fun clearBetweenTokenConsumeAndStreamRegistrationCannotPublishAReader() {
        val disposed = AtomicInteger()
        val registry = NativeReadRegistry<String> { disposed.incrementAndGet() }
        val document = Any()
        assertTrue(registry.issue("token", "partition", "key", "snapshot", Long.MAX_VALUE, document))
        val ticket = registry.consume("token")!!
        val registrationGate = CountDownLatch(1)
        val registrationStarted = CountDownLatch(1)
        val wasClosed = AtomicBoolean()
        val registered = AtomicBoolean(true)
        val thread = Thread {
            registrationStarted.countDown()
            registrationGate.await()
            registered.set(registry.register(ticket, Closeable { wasClosed.set(true) }))
        }.apply { isDaemon = true; start() }

        assertTrue(registrationStarted.await(1, TimeUnit.SECONDS))
        assertEquals(1, registry.revoke("partition", "key"))
        registrationGate.countDown()
        thread.join(1000)

        assertFalse(thread.isAlive)
        assertFalse(registered.get())
        assertTrue(wasClosed.get())
        assertEquals(1, disposed.get())
        assertEquals(0, registry.size())
    }

    @Test fun revokeWaitsForAnInFlightReadAndClosesBeforeReturning() {
        val registry = NativeReadRegistry<String> {}
        val ticket = registry.run {
            issue("token", "partition", "key", "snapshot", Long.MAX_VALUE, Any())
            consume("token")!!
        }
        val streamClosed = AtomicBoolean()
        assertTrue(registry.register(ticket, Closeable { streamClosed.set(true) }))
        val enteredRead = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        val read = Thread {
            registry.read(ticket) { enteredRead.countDown(); finishRead.await(); 1 }
        }.apply { isDaemon = true; start() }
        assertTrue(enteredRead.await(1, TimeUnit.SECONDS))
        val revoked = CountDownLatch(1)
        val clearStarted = CountDownLatch(1)
        val clear = Thread { clearStarted.countDown(); registry.revoke("partition"); revoked.countDown() }.apply { isDaemon = true; start() }
        assertTrue(clearStarted.await(1, TimeUnit.SECONDS))
        val blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (clear.state != Thread.State.BLOCKED && System.nanoTime() < blockedDeadline) Thread.yield()
        assertEquals("clear is blocked on the in-flight read's registry critical section", Thread.State.BLOCKED, clear.state)
        assertFalse("clear must wait for the in-flight read", revoked.await(50, TimeUnit.MILLISECONDS))
        finishRead.countDown()
        assertTrue(revoked.await(1, TimeUnit.SECONDS))
        assertTrue(streamClosed.get())
        assertNull("a revoked ticket cannot read even if a caller retained it", registry.read(ticket) { 1 })
        read.join(1000); clear.join(1000)
    }
}
