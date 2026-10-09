package com.labteto.dshmobile.browser

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PrivateFileTransferTest {
    @get:Rule val temp = TemporaryFolder()
    private val label = "a".repeat(64)
    private val key = "b".repeat(64)

    @Test fun binaryFramesAckSequentiallyAndCommitExactBytes() {
        val store = PrivateFileStore(temp.newFolder("private"), "owner")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        val transfer = PrivateFileTransfer(store)
        try {
            val started = transfer.begin(partition, key, 6, "application/octet-stream")!!
            val chunks = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4), byteArrayOf(5, 6))
            chunks.forEachIndexed { sequence, chunk ->
                val frame = PrivateFileTransfer.frame(started.transferId, sequence, chunk)!!
                val ack = transfer.acceptFrame(frame).get(2, TimeUnit.SECONDS)
                assertEquals(sequence + 1, ack.nextSequence)
                assertNull(ack.terminalError)
            }
            assertEquals(6L, transfer.commit(started.transferId)!!.bytes)
            val snapshot = store.openSnapshot(partition, key)!!
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), store.open(snapshot) { true }!!.use { it.readBytes() })
        } finally { transfer.close() }
    }

    @Test fun outOfOrderFrameTerminatesTransferAndNeverPublishes() {
        val store = PrivateFileStore(temp.newFolder("private"), "owner")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        val transfer = PrivateFileTransfer(store)
        try {
            val started = transfer.begin(partition, key, 4, "application/octet-stream")!!
            val ack = transfer.acceptFrame(PrivateFileTransfer.frame(started.transferId, 1, byteArrayOf(1, 2))!!).get(2, TimeUnit.SECONDS)
            assertEquals("invalid_request", ack.terminalError)
            assertNull(transfer.commit(started.transferId))
            assertNull(store.lookup(partition, key))
        } finally { transfer.close() }
    }

    @Test fun activeTransferAndQueuedBytesHaveHardBounds() {
        val store = PrivateFileStore(temp.newFolder("private"), "owner")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        val transfer = PrivateFileTransfer(store)
        try {
            val first = transfer.begin(partition, key, 1, "application/octet-stream")!!
            assertNotNull(transfer.begin(partition, "c".repeat(64), 1, "application/octet-stream"))
            assertNull(transfer.begin(partition, "d".repeat(64), 1, "application/octet-stream"))
            val invalid = ByteArray(PrivateFileTransfer.HEADER_BYTES + PrivateFileStore.CHUNK_BYTES + 1)
            assertEquals("invalid_request", transfer.acceptFrame(invalid).get(2, TimeUnit.SECONDS).terminalError)
            transfer.abort(first.transferId)
        } finally { transfer.close() }
    }

    @Test fun terminalFutureCallbackDoesNotHoldTransferLockWhileWaitingForServiceLock() {
        val store = PrivateFileStore(temp.newFolder("private"), "owner")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        val appendEntered = CountDownLatch(1)
        val releaseAppend = CountDownLatch(1)
        val transfer = PrivateFileTransfer(store, beforeAppend = { appendEntered.countDown(); releaseAppend.await() })
        val serviceLock = Any()
        val callbackStarted = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val uiFinished = CountDownLatch(1)
        val uiAlive = AtomicBoolean()
        try {
            val started = transfer.begin(partition, key, 1, "application/octet-stream")!!
            transfer.acceptFrame(PrivateFileTransfer.frame(started.transferId, 0, byteArrayOf(1))!!)
                .whenComplete { _, _ ->
                    callbackStarted.countDown()
                    synchronized(serviceLock) { callbackFinished.countDown() }
                }
            assertTrue(appendEntered.await(1, TimeUnit.SECONDS))
            val ui = Thread {
                synchronized(serviceLock) {
                    releaseAppend.countDown()
                    if (callbackStarted.await(1, TimeUnit.SECONDS)) transfer.cancelPartition(partition)
                }
                uiFinished.countDown()
            }.apply { isDaemon = true; start() }
            assertTrue("UI retirement must acquire the transfer lock", uiFinished.await(2, TimeUnit.SECONDS))
            assertTrue("completion callback must acquire the service lock", callbackFinished.await(2, TimeUnit.SECONDS))
            ui.join(1000)
            uiAlive.set(ui.isAlive)
            assertFalse("lock order must not deadlock", uiAlive.get())
        } finally { releaseAppend.countDown(); transfer.close() }
    }

    @Test fun commitWhileBusyLeavesTransferActiveForRetry() {
        val store = PrivateFileStore(temp.newFolder("private"), "owner")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        val appendEntered = CountDownLatch(1)
        val releaseAppend = CountDownLatch(1)
        val transfer = PrivateFileTransfer(store, beforeAppend = { appendEntered.countDown(); releaseAppend.await() })
        try {
            val started = transfer.begin(partition, key, 1, "application/octet-stream")!!
            val future = transfer.acceptFrame(PrivateFileTransfer.frame(started.transferId, 0, byteArrayOf(1))!!)
            assertTrue(appendEntered.await(1, TimeUnit.SECONDS))
            assertTrue(transfer.isActive(started.transferId))
            assertNull(transfer.commit(started.transferId))
            assertTrue(transfer.isActive(started.transferId))
            releaseAppend.countDown()
            assertNull(future.get(2, TimeUnit.SECONDS).terminalError)
            assertEquals(1L, transfer.commit(started.transferId)!!.bytes)
            assertFalse(transfer.isActive(started.transferId))
        } finally { releaseAppend.countDown(); transfer.close() }
    }

    @Test fun commitAfterTotalTimeoutTerminatesTransfer() {
        var now = 0L
        val store = PrivateFileStore(temp.newFolder("private"), "owner")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        val transfer = PrivateFileTransfer(store, clockMs = { now })
        try {
            val started = transfer.begin(partition, key, 1, "application/octet-stream")!!
            assertTrue(transfer.isActive(started.transferId))
            now = PrivateFileTransfer.MAX_TOTAL_MS + 1
            assertNull(transfer.commit(started.transferId))
            assertFalse(transfer.isActive(started.transferId))
            assertNull(store.lookup(partition, key))
        } finally { transfer.close() }
    }

    @Test fun ackCallbackCanQueueNextFullFrameWhileAnotherTransferFrameIsWaiting() {
        val store = PrivateFileStore(temp.newFolder("private"), "owner")
        val partition = store.partitionId(PrivateFileStore.NAMESPACE, label)!!
        val appendEntered = CountDownLatch(1)
        val releaseAppend = CountDownLatch(1)
        val appendCalls = AtomicInteger()
        val transfer = PrivateFileTransfer(store, beforeAppend = {
            if (appendCalls.getAndIncrement() == 0) { appendEntered.countDown(); releaseAppend.await() }
        })
        val a1Future = AtomicReference<java.util.concurrent.CompletableFuture<PrivateFileTransfer.Ack>>()
        try {
            val a = transfer.begin(partition, key, 2L * PrivateFileStore.CHUNK_BYTES, "application/octet-stream")!!
            val b = transfer.begin(partition, "c".repeat(64), PrivateFileStore.CHUNK_BYTES.toLong(), "application/octet-stream")!!
            val payload = ByteArray(PrivateFileStore.CHUNK_BYTES) { it.toByte() }
            val a0 = transfer.acceptFrame(PrivateFileTransfer.frame(a.transferId, 0, payload)!!)
            assertTrue(appendEntered.await(1, TimeUnit.SECONDS))
            val b0 = transfer.acceptFrame(PrivateFileTransfer.frame(b.transferId, 0, payload)!!)
            a0.whenComplete { ack, _ ->
                if (ack != null && ack.terminalError == null) {
                    a1Future.set(transfer.acceptFrame(PrivateFileTransfer.frame(a.transferId, 1, payload)!!))
                }
            }

            releaseAppend.countDown()
            assertNull(a0.get(2, TimeUnit.SECONDS).terminalError)
            assertNull(b0.get(2, TimeUnit.SECONDS).terminalError)
            val a1 = requireNotNull(a1Future.get()) { "A0 ACK callback must enqueue A1" }
            val ack = a1.get(2, TimeUnit.SECONDS)
            assertNull("the ACK callback's next frame fits after A0 releases its reservation", ack.terminalError)
            assertEquals(2, ack.nextSequence)
        } finally { releaseAppend.countDown(); transfer.close() }
    }
}
