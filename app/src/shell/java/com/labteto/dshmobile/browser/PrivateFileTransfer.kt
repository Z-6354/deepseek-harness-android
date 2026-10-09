package com.labteto.dshmobile.browser

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Bounded binary upload protocol. Callbacks run on its IO executor, never the WebView thread. */
class PrivateFileTransfer(
    private val store: PrivateFileStore,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    scheduler: ScheduledExecutorService? = null,
    private val beforeAppend: () -> Unit = {},
) : Closeable {
    data class Started(val transferId: String, val chunkBytes: Int)
    data class Ack(val transferId: String, val nextSequence: Int, val writtenBytes: Long, val terminalError: String? = null)
    private data class Active(
        val id: String, val partition: String, val key: String, val write: PrivateFileStore.Write,
        val startedAt: Long, var lastProgress: Long, var nextSequence: Int = 0, var busy: Boolean = false,
    )
    private val lock = Any()
    private val ownsScheduler = scheduler == null
    private val expiryScheduler = scheduler ?: Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "private-file-expiry").apply { isDaemon = true } }
    private val active = linkedMapOf<String, Active>()
    private val pendingBytes = AtomicInteger()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "private-file-io").apply { isDaemon = true } }
    @Volatile private var closed = false
    private val timer = expiryScheduler.scheduleAtFixedRate({ expireIdle() }, 1, 1, TimeUnit.SECONDS)

    fun begin(partition: String, key: String, bytes: Long, mime: String): Started? = synchronized(lock) {
        if (closed || active.size >= MAX_TRANSFERS) return@synchronized null
        val write = store.beginWrite(partition, key, bytes, mime) ?: return@synchronized null
        val id = randomId()
        val now = clockMs()
        active[id] = Active(id, partition, key, write, now, now)
        Started(id, PrivateFileStore.CHUNK_BYTES)
    }

    fun acceptFrame(frame: ByteArray): CompletableFuture<Ack> {
        val result = CompletableFuture<Ack>()
        if (frame.size < 16) {
            result.complete(Ack("", 0, 0, "invalid_request")); return result
        }
        val id = frame.copyOfRange(0, 16).toHex()
        if (closed) { result.complete(Ack(id, 0, 0, "expired")); return result }
        if (frame.size < HEADER_BYTES + 1 || frame.size > HEADER_BYTES + PrivateFileStore.CHUNK_BYTES) {
            result.complete(terminate(id, "invalid_request")); return result
        }
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        buffer.position(16)
        val sequence = buffer.int
        val length = buffer.int.toLong() and 0xffffffffL
        if (length != (frame.size - HEADER_BYTES).toLong()) {
            result.complete(terminate(id, "invalid_request")); return result
        }
        val payload = ByteArray(length.toInt()).also(buffer::get)
        var immediate: Ack? = null
        synchronized(lock) {
            val transfer = active[id]
            if (transfer == null) {
                immediate = Ack(id, 0, 0, "expired")
                return@synchronized
            }
            if (transfer.busy || sequence != transfer.nextSequence) {
                immediate = terminateLocked(transfer, "invalid_request")
                return@synchronized
            }
            val queued = pendingBytes.addAndGet(payload.size)
            if (queued > MAX_QUEUED_BYTES) {
                pendingBytes.addAndGet(-payload.size)
                immediate = terminateLocked(transfer, "quota")
                return@synchronized
            }
            transfer.busy = true
            val copied = payload.copyOf()
            try {
                io.execute {
                    val ack = try {
                        beforeAppend()
                        val written = store.append(transfer.write, sequence, copied)
                        synchronized(lock) {
                            if (active[id] !== transfer || written == null) {
                                terminateLocked(transfer, "invalid_request")
                            } else {
                                transfer.nextSequence++
                                transfer.lastProgress = clockMs()
                                transfer.busy = false
                                Ack(id, transfer.nextSequence, written)
                            }
                        }
                    } catch (_: Exception) {
                        synchronized(lock) { terminateLocked(transfer, "unavailable") }
                    } finally {
                        // Free this frame's budget before completing the Future: its synchronous
                        // ACK callback may enqueue the transfer's next legal frame immediately.
                        pendingBytes.addAndGet(-copied.size)
                    }
                    result.complete(ack)
                }
            } catch (_: Exception) {
                pendingBytes.addAndGet(-payload.size)
                immediate = terminateLocked(transfer, "unavailable")
            }
        }
        immediate?.let(result::complete)
        return result
    }

    fun commit(transferId: String): PrivateFileStore.Entry? = synchronized(lock) {
        val transfer = active[transferId] ?: return@synchronized null
        if (transfer.busy) return@synchronized null
        if (transfer.startedAt + MAX_TOTAL_MS < clockMs()) {
            terminateLocked(transfer, "expired")
            return@synchronized null
        }
        val entry = store.commit(transfer.write)
        active.remove(transferId)
        if (entry == null) store.abort(transfer.write)
        entry
    }

    fun isActive(transferId: String): Boolean = synchronized(lock) { transferId in active }

    fun abort(transferId: String): Boolean = synchronized(lock) {
        val transfer = active.remove(transferId) ?: return@synchronized true
        store.abort(transfer.write)
    }

    fun cancelPartition(partition: String) = synchronized(lock) {
        active.values.filter { it.partition == partition }.toList().forEach { terminateLocked(it, "expired") }
    }

    fun cancelKey(partition: String, key: String) = synchronized(lock) {
        active.values.filter { it.partition == partition && it.key == key }.toList().forEach { terminateLocked(it, "expired") }
    }

    private fun expireIdle() = synchronized(lock) {
        val now = clockMs()
        active.values.filter { now - it.startedAt >= MAX_TOTAL_MS || now - it.lastProgress >= IDLE_TIMEOUT_MS }
            .toList().forEach { terminateLocked(it, "expired") }
    }
    private fun terminate(id: String, error: String): Ack = synchronized(lock) {
        active[id]?.let { terminateLocked(it, error) } ?: Ack(id, 0, 0, error)
    }
    private fun terminateLocked(transfer: Active, error: String): Ack {
        if (active.remove(transfer.id) != null) store.abort(transfer.write)
        return Ack(transfer.id, transfer.nextSequence, transfer.write.temp.length(), error)
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            active.values.toList().forEach { terminateLocked(it, "expired") }
        }
        timer.cancel(false)
        io.shutdown() // Drain at most the two accepted frames; each future then reaches one terminal state.
        if (ownsScheduler) expiryScheduler.shutdownNow()
    }

    companion object {
        const val MAX_TRANSFERS = 2
        const val MAX_QUEUED_BYTES = 512 * 1024
        const val IDLE_TIMEOUT_MS = 10_000L
        const val MAX_TOTAL_MS = 60_000L
        const val HEADER_BYTES = 24
        private val random = SecureRandom()
        fun frame(transferId: String, sequence: Int, payload: ByteArray): ByteArray? {
            if (!transferId.matches(Regex("[a-f0-9]{32}")) || sequence < 0 || payload.isEmpty() || payload.size > PrivateFileStore.CHUNK_BYTES) return null
            val buffer = ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
            buffer.put(transferId.hexToBytes()).putInt(sequence).putInt(payload.size).put(payload)
            return buffer.array()
        }
        private fun randomId(): String = ByteArray(16).also(random::nextBytes).toHex()
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
