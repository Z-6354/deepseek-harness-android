package com.labteto.dshmobile.browser

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** App-private, owner-partitioned immutable file cache with a durable cleanup fence. */
class PrivateFileStore(private val root: File, private val owner: String, private val limits: Limits = Limits()) {
    data class Entry(val bytes: Long, val mime: String, val sha256: String)
    data class Limits(
        val maxEntryBytes: Long = MAX_ENTRY_BYTES,
        val maxPartitionBytes: Long = MAX_PARTITION_BYTES,
        val maxTotalBytes: Long = MAX_TOTAL_BYTES,
        val maxFiles: Int = MAX_FILES,
    )
    class Write internal constructor(
        val id: String, val key: String, val bytes: Long, val mime: String,
        internal val partition: String, internal val epoch: String, internal val temp: File,
    ) {
        internal var nextSequence = 0
        internal var writtenBytes = 0L
        internal val digest = MessageDigest.getInstance("SHA-256")
    }
    class Snapshot internal constructor(val entry: Entry, internal val file: File, internal val revision: String, internal val pinKey: String) {
        internal val released = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private val state = sharedState(root.absolutePath)
    private val lock get() = state.lock
    private val activeWrites get() = state.activeWrites
    private val pins get() = state.pins
    private val writersReserved get() = state.writersReserved

    init {
        synchronized(lock) {
            root.mkdirs()
            val fence = fenceFile()
            if (!fence.exists()) writeFence(Fence(UUID.randomUUID().toString(), false))
        }
    }

    fun isAvailable(): Boolean = synchronized(lock) { !blocked() && root.isDirectory }
    fun currentEpoch(): String = synchronized(lock) { readFence().epoch }

    /** Remove uncommitted debris after process death; callers run this on their IO executor. */
    fun recoverOrphanWrites() = synchronized(lock) {
        val data = dataRoot()
        if (!data.isDirectory) return@synchronized
        val referenced = mutableSetOf<String>()
        data.walkTopDown().filter { it.isFile && it.extension == "meta" }.forEach { metadataFile ->
            val partition = metadataFile.parentFile?.name ?: return@forEach
            val key = metadataFile.name.removeSuffix(".meta")
            val meta = readMeta(metadataFile)
            if (meta == null) metadataFile.delete()
            else referenced += entryFile(partition, key, meta.revision).absolutePath
        }
        val pinned = pins.entries.filter { it.value > 0 }.mapNotNull { (pin, _) ->
            val segments = pin.split('/')
            if (segments.size == 3) entryFile(segments[0], segments[1], segments[2]).absolutePath else null
        }.toSet()
        val liveTemps = activeWrites.values.map { it.temp.absolutePath }.toSet()
        data.walkTopDown().filter { it.isFile }.forEach { file ->
            when {
                file.extension == "part" && file.absolutePath !in liveTemps -> file.delete()
                file.extension == "tmp" -> file.delete()
                file.extension == "bin" && file.absolutePath !in referenced && file.absolutePath !in pinned -> file.delete()
            }
        }
    }

    /** Persist the fence before revoking in-memory operations. Safe to call again after a crash. */
    fun beginCleanup(nonce: String = UUID.randomUUID().toString()): String = synchronized(lock) {
        val old = readFence()
        val next = if (old.blocked && old.epoch != CORRUPT_EPOCH && old.cleanupNonce == nonce) old
        else Fence(UUID.randomUUID().toString(), true, nonce)
        writeFence(next)
        activeWrites.values.forEach { it.temp.delete() }
        activeWrites.clear(); writersReserved.clear()
        next.epoch
    }

    /** The caller completes this only after WebStorage deletion succeeds. */
    fun completeCleanup(): Boolean = synchronized(lock) {
        deleteForCleanupLocked() && finishCleanupLocked()
    }

    /**
     * Self-heal for an unreadable fence (torn write, disk corruption). Every entry here is a re-fetchable
     * cache, so the safe recovery is to drop the data and start a fresh epoch.
     *
     * Only a *corrupt* fence is touched. A fence carrying a cleanup nonce belongs to a running cleanup
     * transaction and is left alone, so this can never complete or cancel someone else's cleanup.
     * Data goes first and the new fence last: a crash in between leaves the fence corrupt, and the next
     * call simply repeats this. Returns true only when the store was actually rebuilt.
     */
    fun repairCorruptFence(): Boolean = synchronized(lock) {
        if (readFence().epoch != CORRUPT_EPOCH) return@synchronized false
        activeWrites.values.forEach { it.temp.delete() }
        activeWrites.clear(); writersReserved.clear(); pins.clear()
        val data = dataRoot()
        if (data.exists() && !data.deleteRecursively()) return@synchronized false
        runCatching { writeFence(Fence(UUID.randomUUID().toString(), false)); true }.getOrDefault(false)
    }

    fun deleteForCleanup(nonce: String? = null): Boolean = synchronized(lock) { deleteForCleanupLocked(nonce) }

    fun finishCleanup(nonce: String? = null): Boolean = synchronized(lock) { finishCleanupLocked(nonce) }

    private fun deleteForCleanupLocked(nonce: String? = null): Boolean {
        val current = readFence()
        if (!current.blocked || (nonce != null && current.cleanupNonce != nonce)) return false
        activeWrites.values.forEach { it.temp.delete() }
        activeWrites.clear(); writersReserved.clear(); pins.clear()
        val data = File(root, DATA_DIR)
        return !data.exists() || data.deleteRecursively()
    }
    private fun finishCleanupLocked(nonce: String? = null): Boolean {
        val current = readFence()
        if (nonce != null && current.cleanupNonce != nonce) return false
        if (!current.blocked) return true
        if (File(root, DATA_DIR).exists()) return false
        return runCatching { writeFence(Fence(current.epoch, false, current.cleanupNonce)); true }.getOrDefault(false)
    }

    fun partitionId(namespace: String, bindingLabel: String): String? = synchronized(lock) {
        if (blocked() || namespace != NAMESPACE || !HEX64.matches(bindingLabel)) return@synchronized null
        digest("$owner\n$namespace\n$bindingLabel")
    }

    fun lookup(partition: String, key: String): Entry? = synchronized(lock) {
        if (!valid(partition, key)) return@synchronized null
        val metadata = metaFile(partition, key)
        val meta = readMeta(metadata) ?: return@synchronized null
        val file = entryFile(partition, key, meta.revision)
        if (!file.isFile || file.length() != meta.bytes || !HEX64.matches(meta.sha256)) return@synchronized null
        if (hashFile(file) != meta.sha256) { removeVersion(file); metadata.delete(); return@synchronized null }
        metadata.setLastModified(System.currentTimeMillis())
        Entry(meta.bytes, meta.mime, meta.sha256)
    }

    fun beginWrite(partition: String, key: String, bytes: Long, mime: String): Write? = synchronized(lock) {
        if (!valid(partition, key) || bytes < 1 || bytes > limits.maxEntryBytes || mime !in MIME_TYPES) return@synchronized null
        if (mime.startsWith("image/") && bytes < 8) return@synchronized null
        val fence = readFence()
        val id = UUID.randomUUID().toString().replace("-", "")
        val tmp = File(partitionDir(partition), ".$key.$id.part")
        if (!tmp.parentFile!!.exists() && !tmp.parentFile!!.mkdirs()) return@synchronized null
        if (!tmp.createNewFile()) return@synchronized null
        val write = Write(id, key, bytes, mime, partition, fence.epoch, tmp)
        activeWrites[id] = write
        writersReserved[id] = bytes
        if (!withinQuota(partition, additional = 0, replaceKey = key)) {
            evictForQuota(partition, bytes, key)
            if (!withinQuota(partition, additional = 0, replaceKey = key)) {
                activeWrites.remove(id); writersReserved.remove(id); tmp.delete(); return@synchronized null
            }
        }
        write
    }

    fun append(write: Write, sequence: Int, data: ByteArray): Long? = synchronized(lock) {
        if (!isCurrent(write) || sequence < 0 || data.isEmpty() || data.size > CHUNK_BYTES) return@synchronized null
        if (sequence != write.nextSequence || write.writtenBytes + data.size > write.bytes) return@synchronized null
        return@synchronized runCatching {
            FileOutputStream(write.temp, true).use { out -> out.write(data) }
            write.digest.update(data)
            write.writtenBytes += data.size
            write.nextSequence++
            write.writtenBytes
        }.getOrNull()
    }

    fun commit(write: Write): Entry? = synchronized(lock) {
        if (!isCurrent(write) || write.writtenBytes != write.bytes || write.temp.length() != write.bytes) return@synchronized null
        val actualMime = verifyMime(write.mime, write.temp) ?: return@synchronized null
        val hash = write.digest.digest().joinToString("") { "%02x".format(it) }
        val revision = UUID.randomUUID().toString().replace("-", "")
        val target = entryFile(write.partition, write.key, revision)
        if (!target.parentFile!!.exists() && !target.parentFile!!.mkdirs()) return@synchronized null
        val latestFence = readFence()
        if (latestFence.blocked || latestFence.epoch != write.epoch) return@synchronized null
        try {
            FileOutputStream(write.temp, true).use { it.fd.sync() }
            moveAtomically(write.temp, target)
            val meta = Meta(write.bytes, actualMime, hash, revision)
            writeMeta(metaFile(write.partition, write.key), meta)
            // Keep pinned prior versions alive until their final reader closes.
            cleanupOldVersions(write.partition, write.key, revision)
            activeWrites.remove(write.id); writersReserved.remove(write.id)
            Entry(write.bytes, actualMime, hash)
        } catch (_: Exception) {
            target.delete(); null
        }
    }

    fun abort(write: Write): Boolean = synchronized(lock) {
        val removed = activeWrites.remove(write.id) != null
        writersReserved.remove(write.id); write.temp.delete(); removed || !write.temp.exists()
    }

    fun openSnapshot(partition: String, key: String): Snapshot? = synchronized(lock) {
        if (!valid(partition, key)) return@synchronized null
        val meta = readMeta(metaFile(partition, key)) ?: return@synchronized null
        val file = entryFile(partition, key, meta.revision)
        if (!file.isFile || file.length() != meta.bytes || hashFile(file) != meta.sha256) return@synchronized null
        val revKey = "$partition/$key/${meta.revision}"
        pins[revKey] = (pins[revKey] ?: 0) + 1
        val pinKey = "$partition/$key/${meta.revision}"
        Snapshot(Entry(meta.bytes, meta.mime, meta.sha256), file, meta.revision, pinKey)
    }

    fun open(snapshot: Snapshot, stillValid: () -> Boolean): InputStream? {
        val input = runCatching { FileInputStream(snapshot.file) }.getOrNull() ?: run { release(snapshot); return null }
        return object : InputStream() {
            private var closed = false
            override fun read(): Int { if (!allowed()) return fail(); return try { input.read() } catch (_: Exception) { fail() } }
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (!allowed()) return fail(); return try { input.read(b, off, len) } catch (_: Exception) { fail() } }
            private fun allowed() = !closed && stillValid() && synchronized(lock) { !blocked() }
            private fun fail(): Int { close(); return -1 }
            override fun close() { if (!closed) { closed = true; runCatching { input.close() }; release(snapshot) } }
        }
    }

    fun release(snapshot: Snapshot) = synchronized(lock) {
        if (snapshot.released.compareAndSet(false, true)) releaseByKeyLocked(snapshot.pinKey)
    }

    fun remove(partition: String, key: String): Boolean = synchronized(lock) {
        if (!valid(partition, key)) return@synchronized false
        activeWrites.values.filter { it.partition == partition && it.key == key }.toList().forEach { abort(it) }
        val meta = readMeta(metaFile(partition, key)) ?: return@synchronized true
        metaFile(partition, key).delete()
        cleanupOldVersions(partition, key, null)
        true
    }

    fun clearPartition(partition: String): Boolean = synchronized(lock) {
        if (blocked() || !validPartition(partition)) return@synchronized false
        activeWrites.values.filter { it.partition == partition }.toList().forEach { abort(it) }
        val dir = partitionDir(partition)
        dir.listFiles()?.filter { it.isFile && it.extension == "meta" }?.forEach { metaFile ->
            val key = metaFile.name.removeSuffix(".meta")
            metaFile.delete(); cleanupOldVersions(partition, key, null)
        }
        true
    }

    private fun isCurrent(write: Write): Boolean = activeWrites[write.id] == write && !blocked() && readFence().epoch == write.epoch &&
        write.temp.isFile && write.temp.length() <= write.bytes
    private fun valid(partition: String, key: String) = !blocked() && validPartition(partition) && HEX64.matches(key)
    private fun validPartition(partition: String) = HEX64.matches(partition)
    private fun blocked() = readFence().blocked
    private data class Fence(val epoch: String, val blocked: Boolean, val cleanupNonce: String? = null)
    private data class Meta(val bytes: Long, val mime: String, val sha256: String, val revision: String)
    private fun fenceFile() = File(root, "fence.properties")
    private fun readFence(): Fence {
        val props = Properties()
        if (!fenceFile().isFile) return Fence(CORRUPT_EPOCH, true)
        val loaded = runCatching { fenceFile().inputStream().use(props::load); true }.getOrDefault(false)
        val epoch = props.getProperty("epoch")
        val blockedValue = props.getProperty("blocked")
        if (!loaded || epoch.isNullOrBlank() || epoch == CORRUPT_EPOCH || blockedValue !in setOf("true", "false")) return Fence(CORRUPT_EPOCH, true)
        val cleanupNonce = props.getProperty("cleanupNonce")
        return Fence(epoch, blockedValue == "true", cleanupNonce)
    }
    private fun writeFence(fence: Fence) { writeProperties(fenceFile(), Properties().apply {
        setProperty("epoch", fence.epoch); setProperty("blocked", fence.blocked.toString())
        fence.cleanupNonce?.let { setProperty("cleanupNonce", it) }
    }) }
    private fun dataRoot() = File(root, DATA_DIR)
    private fun partitionDir(partition: String) = File(dataRoot(), partition)
    private fun entryFile(partition: String, key: String, rev: String) = File(partitionDir(partition), "$key.$rev.bin")
    private fun metaFile(partition: String, key: String) = File(partitionDir(partition), "$key.meta")
    private fun readMeta(file: File): Meta? = runCatching { Properties().also { file.inputStream().use(it::load) }.let {
        Meta(it.getProperty("bytes").toLong(), it.getProperty("mime"), it.getProperty("sha256"), it.getProperty("revision"))
    } }.getOrNull()?.takeIf { it.bytes in 1..MAX_ENTRY_BYTES && it.mime in MIME_TYPES && HEX64.matches(it.sha256) && it.revision.matches(Regex("[a-f0-9]{32}")) }
    private fun writeMeta(file: File, meta: Meta) = writeProperties(file, Properties().apply {
        setProperty("bytes", meta.bytes.toString()); setProperty("mime", meta.mime); setProperty("sha256", meta.sha256); setProperty("revision", meta.revision)
    })
    private fun writeProperties(file: File, properties: Properties) {
        check(file.parentFile!!.exists() || file.parentFile!!.mkdirs())
        val tmp = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        FileOutputStream(tmp).use { properties.store(it, null); it.fd.sync() }
        moveAtomically(tmp, file)
    }
    private fun moveAtomically(from: File, to: File) {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: Exception) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
    private fun hashFile(file: File): String = FileInputStream(file).use { input ->
        val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; md.update(buffer, 0, count) }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun verifyMime(mime: String, file: File): String? {
        if (mime == "application/octet-stream") return mime
        val header = ByteArray(minOf(16L, file.length()).toInt())
        FileInputStream(file).use { if (it.read(header) != header.size) return null }
        val valid = when (mime) {
            "image/png" -> header.size >= 8 && header.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            "image/jpeg" -> header.size >= 3 && header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()
            "image/gif" -> header.size >= 6 && String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
            "image/webp" -> header.size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
            else -> false
        }
        return mime.takeIf { valid }
    }
    private fun cleanupOldVersions(partition: String, key: String, keep: String?) {
        val dir = partitionDir(partition)
        dir.listFiles()?.filter { it.name.startsWith("$key.") && it.name.endsWith(".bin") }?.forEach { file ->
            val rev = file.name.removePrefix("$key.").removeSuffix(".bin")
            if (rev != keep && (pins["$partition/$key/$rev"] ?: 0) == 0) file.delete()
        }
    }
    private fun removeVersion(file: File) { file.delete() }
    private fun releaseByKey(key: String) = synchronized(lock) { releaseByKeyLocked(key) }
    private fun releaseByKeyLocked(key: String) {
        val count = pins[key] ?: return
        if (count <= 1) pins.remove(key) else pins[key] = count - 1
        val parts = key.split('/')
        if (parts.size == 3) cleanupOldVersions(parts[0], parts[1], readMeta(metaFile(parts[0], parts[1]))?.revision)
    }
    private fun withinQuota(partition: String, additional: Long, replaceKey: String? = null): Boolean {
        val reserved = writersReserved.values.sum()
        val all = dataRoot().walkTopDown().filter { it.isFile && it.extension == "bin" }.sumOf { it.length() } + reserved
        val partitionBytes = partitionDir(partition).walkTopDown().filter { it.isFile && it.extension == "bin" }.sumOf { it.length() } +
            writersReserved.filterKeys { activeWrites[it]?.partition == partition }.values.sum()
        val count = dataRoot().walkTopDown().count { it.isFile && it.extension == "meta" }
        val replacing = replaceKey != null && metaFile(partition, replaceKey).isFile
        return additional <= limits.maxPartitionBytes && all + additional <= limits.maxTotalBytes && partitionBytes + additional <= limits.maxPartitionBytes &&
            (count < limits.maxFiles || replacing)
    }
    private fun evictForQuota(partition: String, @Suppress("UNUSED_PARAMETER") incoming: Long, replaceKey: String) {
        val files = dataRoot().walkTopDown().filter { it.isFile && it.extension == "meta" }
            .sortedBy { it.lastModified() }.toList()
        for (file in files) {
            if (currentBytes(partition) <= limits.maxPartitionBytes && totalBytes() <= limits.maxTotalBytes && withinQuota(partition, 0, replaceKey = replaceKey)) break
            val part = file.parentFile?.name ?: continue
            val key = file.name.removeSuffix(".meta")
            val meta = readMeta(file)
            if (meta == null) { file.delete(); continue }
            if (part == partition && key == replaceKey) continue // Keep the old entry until replacement publication succeeds.
            if (part == partition && currentBytes(partition) <= limits.maxPartitionBytes && totalBytes() <= limits.maxTotalBytes && withinQuota(partition, 0, replaceKey = replaceKey)) continue
            if ((pins["$part/$key/${meta.revision}"] ?: 0) > 0) continue
            file.delete(); cleanupOldVersions(part, key, null)
        }
    }
    private fun currentBytes(partition: String) = partitionDir(partition).walkTopDown().filter { it.isFile && it.extension == "bin" }.sumOf { it.length() } +
        writersReserved.filterKeys { activeWrites[it]?.partition == partition }.values.sum()
    private fun totalBytes() = dataRoot().walkTopDown().filter { it.isFile && it.extension == "bin" }.sumOf { it.length() } + writersReserved.values.sum()
    companion object {
        const val NAMESPACE = "private-cache-v1"
        const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
        const val MAX_PARTITION_BYTES = 128L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
        const val MAX_FILES = 1024
        const val CHUNK_BYTES = 256 * 1024
        private const val DATA_DIR = "data"
        /** Epoch reported for a missing/unreadable fence. A stored fence can never claim it (see readFence). */
        private const val CORRUPT_EPOCH = "invalid"
        private val HEX64 = Regex("[a-f0-9]{64}")
        val MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif", "application/octet-stream")
        private data class SharedState(
            val lock: Any = Any(),
            val activeWrites: MutableMap<String, Write> = linkedMapOf(),
            val pins: MutableMap<String, Int> = mutableMapOf(),
            val writersReserved: MutableMap<String, Long> = mutableMapOf(),
        )
        private val rootStates = java.util.concurrent.ConcurrentHashMap<String, SharedState>()
        private fun sharedState(path: String) = rootStates.computeIfAbsent(path) { SharedState() }
        /** Drops process-only pins/active-write state so JVM recovery tests can model process death. */
        internal fun resetSharedStateForProcessRestart(root: File) { rootStates.remove(root.absolutePath) }
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
