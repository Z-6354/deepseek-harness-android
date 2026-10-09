package com.labteto.dshmobile.browser

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Per-WebView private file controls, binary listener and one-use same-origin read capabilities. */
class PrivateFileService(
    private val context: android.content.Context,
    private val webView: WebView,
    private val site: Site,
    private val currentLease: () -> DocumentLease,
    private val ownsLease: (DocumentLease) -> Boolean,
    private val reply: (DocumentLease, String) -> Unit,
    private val elapsedMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : Closeable {
    private data class ReadToken(
        val id: String, val partition: String, val key: String, val snapshot: PrivateFileStore.Snapshot,
        val lease: DocumentLease, val epoch: String, val expiresAt: Long,
    )
    private data class Probe(val id: String, val bytes: ByteArray, val lease: DocumentLease, val expiresAt: Long, var binary: Boolean = false, var got: Boolean = false)

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "private-file-control").apply { isDaemon = true } }
    private val expiry: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "private-file-read-expiry").apply { isDaemon = true } }
    private val storeLock = Any()
    @Volatile private var cachedStore: PrivateFileStore? = null
    @Volatile private var storageUnavailable = false
    private fun getOrInitializeStore(): PrivateFileStore = cachedStore ?: synchronized(storeLock) {
        cachedStore ?: try {
            PrivateFileStore(File(context.noBackupFilesDir, "private-cache-v1"), site.owner).also {
                it.recoverOrphanWrites()
                storageUnavailable = !it.isAvailable()
                cachedStore = it
            }
        } catch (error: Exception) {
            storageUnavailable = true
            throw error
        }
    }
    private val lock = Any()
    private val partitions = linkedMapOf<String, String>()
    private val readRegistry = NativeReadRegistry<ReadToken> { token -> cachedStore?.release(token.snapshot) }
    private val transferOwners = linkedMapOf<String, String>()
    private val readyDocuments = mutableSetOf<DocumentLease>()
    private val random = SecureRandom()
    private val transfers = lazy { PrivateFileTransfer(getOrInitializeStore()) }
    @Volatile private var documentLease = currentLease()
    @Volatile private var documentIdentity = Any()
    private var probe: Probe? = null
    private var probeAttempted = false
    @Volatile private var closed = false
    @Volatile private var writerInstalled: Boolean = false

    init {
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        writerInstalled = supported && installWriter(documentIdentity)
        expiry.scheduleAtFixedRate({ expireReadTokens() }, 1, 1, TimeUnit.SECONDS)
    }

    fun bindDocument(lease: DocumentLease) {
        val retiredPartitions = synchronized(lock) {
            if (documentLease == lease) return
            val retired = retireDocumentLocked()
            documentLease = lease
            documentIdentity = Any()
            probeAttempted = false
            if (!closed && writerInstalled) installWriter(documentIdentity)
            retired
        }
        if (transfers.isInitialized()) retiredPartitions.forEach(transfers.value::cancelPartition)
    }

    fun capabilities(): kotlinx.serialization.json.JsonObject? {
        if (closed || !writerInstalled || storageUnavailable) return null
        return buildJsonObject {
            put("version", 1); put("transport", "arraybuffer-upload-same-origin-read")
            put("maxEntryBytes", PrivateFileStore.MAX_ENTRY_BYTES); put("chunkBytes", PrivateFileStore.CHUNK_BYTES)
            put("maxTransfers", PrivateFileTransfer.MAX_TRANSFERS)
            put("state", synchronized(lock) { if (currentLease() in readyDocuments) "ready" else "probe-required" })
        }
    }

    /** Returns true when a privateFiles request was accepted and its reply will be delivered asynchronously. */
    fun handle(request: BridgeRequest, lease: DocumentLease, foreground: Boolean): Boolean {
        if (!request.type.startsWith("privateFiles.")) return false
        if (!writerInstalled) { sendError(lease, request.id, "unsupported"); return true }
        val requiresForeground = request.type in setOf("privateFiles.open", "privateFiles.lookup", "privateFiles.beginWrite", "privateFiles.openRead")
        if (requiresForeground && !foreground) { sendError(lease, request.id, "unavailable"); return true }
        val identity = synchronized(lock) { documentIdentity.takeIf { !closed && ownsLease(lease) && lease == documentLease } }
        if (identity == null) { sendError(lease, request.id, "stale_document"); return true }
        io.execute {
            if (!isCurrentDocument(lease, identity)) { sendError(lease, request.id, "stale_document"); return@execute }
            try { process(request, lease, identity) } catch (_: Exception) { sendError(lease, request.id, "unavailable") }
        }
        return true
    }

    private fun process(request: BridgeRequest, lease: DocumentLease, identity: Any) {
        val p = request.payload
        if (cachedStore == null && request.type != "privateFiles.probe") { sendError(lease, request.id, "unsupported"); return }
        val store = cachedStore ?: getOrInitializeStore()
        if (!isCurrentDocument(lease, identity)) { sendError(lease, request.id, "stale_document"); return }
        if (!store.isAvailable()) { storageUnavailable = true; sendError(lease, request.id, "unavailable"); return }
        fun text(name: String): String? = (p[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun exact(vararg keys: String) = p.keys == keys.toSet()
        fun handle(): Pair<String, String>? = text("partitionHandle")?.takeIf { it.matches(HEX32) }?.let { id ->
            synchronized(lock) { if (documentIdentity === identity && id in partitions) id to partitions.getValue(id) else null }
        }
        when (request.type) {
            "privateFiles.probe" -> {
                val step = text("step")
                if (step == "begin" && exact("step")) {
                    synchronized(lock) {
                        if (documentIdentity !== identity || probeAttempted) { sendError(lease, request.id, "expired"); return }
                        probeAttempted = true
                        val id = randomHex(16)
                        val current = Probe(id, ByteArray(32).also(random::nextBytes), lease, elapsedMs() + PROBE_TTL_MS)
                        probe = current
                        val url = "${site.origin}$READ_PREFIX$id"
                        sendResult(lease, request.id, buildJsonObject { put("probeId", id); put("transferId", id); put("url", url) })
                    }
                } else if (step == "confirm" && exact("step", "probeId", "sha256")) {
                    val id = text("probeId")
                    val supplied = text("sha256")
                    synchronized(lock) {
                        val active = probe
                        if (id == null || supplied == null || active == null || active.id != id || active.lease != lease || active.expiresAt < elapsedMs() || !active.binary || !active.got || supplied != sha256(active.bytes)) {
                            probe = null; sendError(lease, request.id, "unsupported"); return
                        }
                        probe = null
                        readyDocuments.add(lease)
                        sendResult(lease, request.id, buildJsonObject { put("state", "ready") })
                    }
                } else sendError(lease, request.id, "invalid_request")
            }
            "privateFiles.open" -> {
                if (!exact("namespace", "bindingLabel")) { sendError(lease, request.id, "invalid_request"); return }
                val namespace = text("namespace") ?: ""
                val label = text("bindingLabel") ?: ""
                val partition = store.partitionId(namespace, label)
                if (partition == null) { sendError(lease, request.id, "invalid_request"); return }
                synchronized(lock) {
                    if (documentIdentity !== identity || lease !in readyDocuments || partitions.size >= MAX_PARTITIONS) { sendError(lease, request.id, "unsupported"); return }
                    val handle = randomHex(16); partitions[handle] = partition
                    sendResult(lease, request.id, buildJsonObject { put("partitionHandle", handle) })
                }
            }
            "privateFiles.lookup" -> {
                if (!exact("partitionHandle", "key")) { sendError(lease, request.id, "invalid_request"); return }
                val (h, partition) = handle() ?: return sendError(lease, request.id, "invalid_request")
                val key = text("key") ?: return sendError(lease, request.id, "invalid_request")
                if (!HEX64.matches(key)) return sendError(lease, request.id, "invalid_request")
                val entry = store.lookup(partition, key)
                if (!isCurrentDocument(lease, identity)) return sendError(lease, request.id, "stale_document")
                if (entry == null) sendResult(lease, request.id, buildJsonObject { put("hit", false) })
                else sendResult(lease, request.id, buildJsonObject { put("hit", true); put("bytes", entry.bytes); put("mime", entry.mime); put("sha256", entry.sha256) })
            }
            "privateFiles.beginWrite" -> {
                if (!exact("partitionHandle", "key", "bytes", "mime")) { sendError(lease, request.id, "invalid_request"); return }
                val (h, partition) = handle() ?: return sendError(lease, request.id, "invalid_request")
                val key = text("key") ?: return sendError(lease, request.id, "invalid_request")
                val bytes = (p["bytes"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                val mime = text("mime") ?: return sendError(lease, request.id, "invalid_request")
                if (!HEX64.matches(key) || bytes == null || bytes !in 1..PrivateFileStore.MAX_ENTRY_BYTES || mime !in PrivateFileStore.MIME_TYPES) return sendError(lease, request.id, "invalid_request")
                val started = transfers.value.begin(partition, key, bytes, mime)
                if (started == null) sendError(lease, request.id, "quota") else {
                    val stale = synchronized(lock) {
                        if (documentIdentity !== identity || !ownsLease(lease)) true
                        else { transferOwners[started.transferId] = h; false }
                    }
                    if (stale) { transfers.value.abort(started.transferId); sendError(lease, request.id, "stale_document"); return }
                    sendResult(lease, request.id, buildJsonObject { put("transferId", started.transferId); put("chunkBytes", started.chunkBytes) })
                }
            }
            "privateFiles.commitWrite" -> {
                if (!exact("transferId")) return sendError(lease, request.id, "invalid_request")
                val id = text("transferId") ?: return sendError(lease, request.id, "invalid_request")
                if (synchronized(lock) { documentIdentity !== identity || id !in transferOwners }) return sendError(lease, request.id, "invalid_request")
                val entry = transfers.value.commit(id)
                // Keep ownership while the transfer is still active (busy); drop it only after
                // success or a terminal failure so abortWrite/commitWrite remain reachable.
                synchronized(lock) {
                    if (entry != null || !transfers.value.isActive(id)) transferOwners.remove(id)
                }
                if (entry == null) sendError(lease, request.id, "integrity") else sendResult(lease, request.id, buildJsonObject { put("bytes", entry.bytes); put("sha256", entry.sha256) })
            }
            "privateFiles.abortWrite" -> {
                if (!exact("transferId")) return sendError(lease, request.id, "invalid_request")
                val id = text("transferId") ?: return sendError(lease, request.id, "invalid_request")
                if (synchronized(lock) { documentIdentity !== identity || id !in transferOwners }) return sendError(lease, request.id, "invalid_request")
                transfers.value.abort(id); synchronized(lock) { transferOwners.remove(id) }
                sendResult(lease, request.id, buildJsonObject {})
            }
            "privateFiles.openRead" -> {
                if (!exact("partitionHandle", "key")) return sendError(lease, request.id, "invalid_request")
                val (h, partition) = handle() ?: return sendError(lease, request.id, "invalid_request")
                val key = text("key") ?: return sendError(lease, request.id, "invalid_request")
                if (!HEX64.matches(key)) return sendError(lease, request.id, "invalid_request")
                if (readRegistry.size() >= MAX_READ_TOKENS) return sendError(lease, request.id, "quota")
                val snapshot = store.openSnapshot(partition, key) ?: return sendError(lease, request.id, "not_found")
                if (!isCurrentDocument(lease, identity)) { store.release(snapshot); return sendError(lease, request.id, "stale_document") }
                val id = randomHex(24)
                val token = ReadToken(id, partition, key, snapshot, lease, store.currentEpoch(), elapsedMs() + READ_TTL_MS)
                synchronized(lock) {
                    if (documentIdentity !== identity || !ownsLease(lease)) { store.release(snapshot); return sendError(lease, request.id, "stale_document") }
                    if (!readRegistry.issue(id, partition, key, token, token.expiresAt, identity)) {
                        store.release(snapshot); return sendError(lease, request.id, "unavailable")
                    }
                }
                sendResult(lease, request.id, buildJsonObject { put("url", "${site.origin}$READ_PREFIX$id"); put("expiresInMs", READ_TTL_MS) })
            }
            "privateFiles.remove" -> {
                if (!exact("partitionHandle", "key")) return sendError(lease, request.id, "invalid_request")
                val (h, partition) = handle() ?: return sendError(lease, request.id, "invalid_request")
                val key = text("key") ?: return sendError(lease, request.id, "invalid_request")
                if (!HEX64.matches(key)) return sendError(lease, request.id, "invalid_request")
                if (transfers.isInitialized()) transfers.value.cancelKey(partition, key)
                revokeReads(partition, key)
                // Hold the service lock across the disk mutation so a retired document cannot
                // delete the next document's same-bindingLabel partition after handles were cleared.
                val removed = synchronized(lock) {
                    if (documentIdentity !== identity || !ownsLease(lease) || partitions[h] != partition) {
                        sendError(lease, request.id, "stale_document"); return
                    }
                    store.remove(partition, key)
                }
                sendResult(lease, request.id, buildJsonObject { put("removed", removed) })
            }
            "privateFiles.clearPartition" -> {
                if (!exact("partitionHandle")) return sendError(lease, request.id, "invalid_request")
                val (h, partition) = handle() ?: return sendError(lease, request.id, "invalid_request")
                revokeReads(partition, null)
                if (transfers.isInitialized()) transfers.value.cancelPartition(partition)
                val cleared = synchronized(lock) {
                    if (documentIdentity !== identity || !ownsLease(lease) || partitions[h] != partition) {
                        sendError(lease, request.id, "stale_document"); return
                    }
                    transferOwners.entries.removeIf { it.value == h }
                    store.clearPartition(partition)
                }
                sendResult(lease, request.id, buildJsonObject { put("cleared", cleared) })
            }
            "privateFiles.releasePartition" -> {
                if (!exact("partitionHandle")) return sendError(lease, request.id, "invalid_request")
                val (h, partition) = handle() ?: return sendError(lease, request.id, "invalid_request")
                revokeReads(partition, null); transfers.value.cancelPartition(partition)
                synchronized(lock) { partitions.remove(h); transferOwners.entries.removeIf { it.value == h } }
                sendResult(lease, request.id, buildJsonObject {})
            }
            else -> sendError(lease, request.id, "invalid_request")
        }
    }

    /** Returns null for ordinary URLs and a local response for every reserved path, including failures. */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val path = uri.encodedPath ?: ""
        if (!path.startsWith(READ_PREFIX)) return null
        fun denied(status: Int = 404, reason: String = "Not Found") = WebResourceResponse("text/plain", null, status, reason,
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff", "Cross-Origin-Resource-Policy" to "same-origin"), ByteArrayInputStream(ByteArray(0)))
        if (closed) return denied()
        if (request.isForMainFrame) return denied(403, "Forbidden")
        if (NavigationPolicy.origin(uri.toString()) != site.origin) return denied()
        if (request.method != "GET" || request.requestHeaders.keys.any { it.equals("Range", true) } || uri.query != null || uri.fragment != null) return denied(400, "Bad Request")
        val tokenId = path.removePrefix(READ_PREFIX)
        val probeBytes = synchronized(lock) {
            val activeProbe = probe?.takeIf { it.id == tokenId && it.lease == documentLease && it.expiresAt >= elapsedMs() } ?: return@synchronized null
            if (!ownsLease(activeProbe.lease) || activeProbe.got) return@synchronized PROBE_DENIED
            activeProbe.got = true
            activeProbe.bytes.copyOf()
        }
        if (probeBytes === PROBE_DENIED) return denied(400, "Bad Request")
        if (probeBytes != null) {
            return WebResourceResponse("application/octet-stream", null, 200, "OK", noStoreHeaders(probeBytes.size.toLong()), ByteArrayInputStream(probeBytes))
        }
        if (!tokenId.matches(HEX48)) return denied()
        val ticket = readRegistry.consume(tokenId) ?: return denied()
        val current = ticket.value
        if (current.expiresAt < elapsedMs() || current.lease != documentLease || !ownsLease(current.lease)) { readRegistry.revoke(ticket); return denied() }
        val store = cachedStore ?: run { readRegistry.revoke(ticket); return denied() }
        val input = store.open(current.snapshot) { current.lease == documentLease && ownsLease(current.lease) && readRegistry.isCurrent(ticket) && store.currentEpoch() == current.epoch }
            ?: run { readRegistry.revoke(ticket); return denied() }
        val tracked = object : FilterInputStream(input) {
            private val done = AtomicBoolean(false)
            override fun read(): Int { val value = readRegistry.read(ticket) { super.read() } ?: -1; if (value < 0) close(); return value }
            override fun read(b: ByteArray, off: Int, len: Int): Int { val count = readRegistry.read(ticket) { super.read(b, off, len) } ?: -1; if (count < 0) close(); return count }
            override fun close() { if (done.compareAndSet(false, true)) { super.close(); readRegistry.finish(ticket) } }
        }
        if (current.lease != documentLease || closed || !readRegistry.register(ticket, tracked)) { tracked.close(); return denied() }
        val headers = noStoreHeaders(current.snapshot.entry.bytes)
        return WebResourceResponse(current.snapshot.entry.mime, null, 200, "OK", headers, tracked)
    }

    fun isReservedPath(url: String): Boolean = runCatching {
        val uri = android.net.Uri.parse(url)
        val expectedHost = android.net.Uri.parse(site.origin).host
        uri.host.equals(expectedHost, ignoreCase = true) && uri.encodedPath?.startsWith(READ_PREFIX) == true
    }.getOrDefault(false)

    fun blocksReservedTopLevel(url: String): Boolean = isReservedPath(url)

    private fun onBinary(view: WebView, message: WebMessageCompat, sourceOrigin: String, mainFrame: Boolean) {
        val identity = documentIdentity
        val lease = documentLease
        if (closed || view !== webView || !mainFrame || NavigationPolicy.origin(sourceOrigin) != site.origin ||
            lease != currentLease() || !ownsLease(lease) || message.type != WebMessageCompat.TYPE_ARRAY_BUFFER) return
        val frame = runCatching { message.arrayBuffer }.getOrNull() ?: return
        if (frame.size < 16) return
        val id = frame.copyOfRange(0, 16).joinToString("") { "%02x".format(it) }
        val seq = if (frame.size >= 20) java.nio.ByteBuffer.wrap(frame, 16, 4).int else -1
        synchronized(lock) {
            val activeProbe = probe
            if (activeProbe != null && activeProbe.id == id) {
                if (frame.size < PrivateFileTransfer.HEADER_BYTES) {
                    probe = null; sendError(lease, "pf:$id:$seq", "invalid_request"); return
                }
                val length = java.nio.ByteBuffer.wrap(frame, 20, 4).int
                if (documentIdentity !== identity || activeProbe.lease != lease || activeProbe.expiresAt < elapsedMs() || activeProbe.binary || seq != 0 || length != 32 || frame.size != 56) {
                    probe = null; return
                }
                activeProbe.binary = true
                sendResult(lease, "pf:$id:0", buildJsonObject { put("nextSequence", 1); put("writtenBytes", 32) })
                return
            }
        }
        if (!transfers.isInitialized()) return
        if (synchronized(lock) { documentIdentity !== identity || id !in transferOwners }) return
        transfers.value.acceptFrame(frame).whenComplete { ack, _ ->
            if (ack == null || !isCurrentDocument(lease, identity)) return@whenComplete
            val correlation = "pf:${ack.transferId}:$seq"
            if (ack.terminalError != null) {
                synchronized(lock) { transferOwners.remove(ack.transferId) }
                sendError(lease, correlation, ack.terminalError)
            } else sendResult(lease, correlation, buildJsonObject { put("nextSequence", ack.nextSequence); put("writtenBytes", ack.writtenBytes) })
        }
    }

    private fun revokeReads(partition: String, key: String?) {
        readRegistry.revoke(partition, key)
    }
    private fun retireDocumentLocked(): Set<String> {
        probe = null; readyDocuments.remove(documentLease)
        val retiredPartitions = partitions.values.toSet()
        partitions.clear(); transferOwners.clear()
        readRegistry.revokeAll()
        return retiredPartitions
    }
    private fun expireReadTokens() {
        val currentIdentity = documentIdentity
        readRegistry.expireTokens(elapsedMs(), currentIdentity)
        synchronized(lock) {
        probe?.takeIf { it.expiresAt <= elapsedMs() }?.let { probe = null }
        }
    }
    private fun sendResult(lease: DocumentLease, id: String, result: kotlinx.serialization.json.JsonObject) {
        if (isCurrentDocument(lease, documentIdentity)) reply(lease, BridgeProtocol.reply(id, result))
    }
    private fun sendError(lease: DocumentLease, id: String, error: String) {
        if (isCurrentDocument(lease, documentIdentity)) reply(lease, BridgeProtocol.reply(id, error = error))
    }
    private fun isCurrentDocument(lease: DocumentLease, identity: Any) = !closed && documentIdentity === identity && documentLease == lease && currentLease() == lease && ownsLease(lease)
    private fun installWriter(identity: Any): Boolean = runCatching {
        runCatching { WebViewCompat.removeWebMessageListener(webView, WRITER_NAME) }
        WebViewCompat.addWebMessageListener(webView, WRITER_NAME, setOf(site.origin)) { view, message, source, mainFrame, _ ->
            if (documentIdentity === identity) onBinary(view, message, source.toString(), mainFrame)
        }
        true
    }.getOrDefault(false)
    private fun randomHex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun noStoreHeaders(length: Long) = mapOf("Content-Length" to length.toString(), "Cache-Control" to "no-store",
        "X-Content-Type-Options" to "nosniff", "Cross-Origin-Resource-Policy" to "same-origin")

    override fun close() {
        val retiredPartitions = synchronized(lock) {
            if (closed) return
            closed = true
            retireDocumentLocked()
        }
        if (transfers.isInitialized()) retiredPartitions.forEach(transfers.value::cancelPartition)
        runCatching { WebViewCompat.removeWebMessageListener(webView, WRITER_NAME) }
        if (transfers.isInitialized()) transfers.value.close()
        io.shutdown(); expiry.shutdownNow()
    }

    companion object {
        const val WRITER_NAME = "HanPrivateFileWriter"
        const val READ_PREFIX = "/.__hanapp_private__/v1/read/"
        const val READ_TTL_MS = 30_000L
        const val PROBE_TTL_MS = 30_000L
        const val MAX_PARTITIONS = 16
        const val MAX_READ_TOKENS = 32
        private val HEX32 = Regex("[a-f0-9]{32}")
        private val HEX48 = Regex("[a-f0-9]{48}")
        private val HEX64 = Regex("[a-f0-9]{64}")
        /** Sentinel from [intercept] when a probe id matched but the one-use check failed. */
        private val PROBE_DENIED = ByteArray(0)
    }
}
