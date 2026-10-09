package com.labteto.dshmobile.browser

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Versioned public JS/CSS are downloaded once into an app-owned local store (not OkHttp cache).
 * First fetch may be slow; later launches stream the local file (no full heap copy on hit).
 */
class StaticAssetCache(directory: File, private val diagnostic: (String) -> Unit = {}, baseClient: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false)
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()) {
    private val store = File(directory, "store").also { it.mkdirs() }
    private val network = baseClient.newBuilder().cache(null).followRedirects(false).followSslRedirects(false).build()
    private val inflight = ConcurrentHashMap<String, CompletableFuture<Result<Stored>>>()
    data class Asset(val mime: String, val headers: Map<String, String>, val stream: InputStream, val size: Int)
    sealed class Intercept {
        data object Skip : Intercept()
        data class Ready(val asset: Asset) : Intercept()
        data class Failed(val status: Int, val reason: String) : Intercept()
    }

    fun load(site: Site, url: String, headers: Map<String, String>, cookie: String?, owns: () -> Boolean): Asset? =
        (intercept(site, url, headers, cookie, owns) as? Intercept.Ready)?.asset

    fun intercept(site: Site, url: String, headers: Map<String, String>, cookie: String?, owns: () -> Boolean): Intercept {
        val parsed = url.toHttpUrlOrNull() ?: return Intercept.Skip
        val entry = site.entryUrl.toHttpUrlOrNull() ?: return Intercept.Skip
        if (!owns() || url.length > 16384 || parsed.scheme != "https" || parsed.host != entry.host || parsed.port != entry.port ||
            parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
            site.staticResourcePrefixes.none { it.startsWith("/") && it.endsWith("/") && parsed.encodedPath.startsWith(it) } ||
            !cacheableUrl(parsed)) return Intercept.Skip
        val key = assetKey(url)
        openLocal(key)?.let { local ->
            diagnostic("asset length=${url.length} local_hit=true bytes=${local.size}")
            return Intercept.Ready(local)
        }
        val created = CompletableFuture<Result<Stored>>()
        val existing = inflight.putIfAbsent(key, created)
        val future = existing ?: created
        if (existing == null) {
            try {
                created.complete(runCatching { downloadAndStore(key, url, headers, cookie, owns) })
            } catch (error: Throwable) {
                created.complete(Result.failure(error))
            } finally {
                inflight.remove(key, created)
            }
        }
        val result = runCatching { future.get(50, TimeUnit.SECONDS) }.getOrElse {
            diagnostic("download_error ${it.javaClass.simpleName}")
            return Intercept.Skip
        }
        return result.fold(
            onSuccess = { stored ->
                diagnostic("asset length=${url.length} local_hit=false bytes=${stored.size}")
                Intercept.Ready(stored.toAsset())
            },
            onFailure = { error ->
                when (error) {
                    is AlreadyStored -> openLocal(key)?.also {
                        diagnostic("asset length=${url.length} local_hit=true bytes=${it.size}")
                    }?.let { Intercept.Ready(it) } ?: Intercept.Failed(502, "Asset Unavailable")
                    is IneligibleAsset -> Intercept.Failed(502, "Asset Unavailable")
                    is OwnershipLost -> Intercept.Skip
                    else -> {
                        diagnostic("download_error ${error.javaClass.simpleName}")
                        Intercept.Skip
                    }
                }
            },
        )
    }

    private data class Stored(val mime: String, val headers: Map<String, String>, val bytes: ByteArray, val size: Int = bytes.size) {
        fun toAsset(): Asset {
            val responseHeaders = headers.toMutableMap()
            responseHeaders.keys.filter { it.equals("Content-Length", true) || it.equals("Content-Encoding", true) }
                .forEach { responseHeaders.remove(it) }
            responseHeaders["Content-Length"] = size.toString()
            return Asset(mime, responseHeaders, java.io.ByteArrayInputStream(bytes), size)
        }
    }
    private class IneligibleAsset : Exception()
    private class OwnershipLost : Exception()
    private class AlreadyStored : Exception()

    private fun downloadAndStore(key: String, url: String, headers: Map<String, String>, cookie: String?, owns: () -> Boolean): Stored {
        if (localPresent(key)) throw AlreadyStored()
        val builder = Request.Builder().url(url).get()
        headers.filterKeys { it.equals("User-Agent", true) || it.equals("Accept", true) }.forEach { (keyName, value) -> builder.header(keyName, value) }
        if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
        val response = network.newCall(builder.build()).execute()
        diagnostic("asset length=${url.length} network_status=${response.code}")
        if (!owns()) {
            response.close()
            throw OwnershipLost()
        }
        if (!eligible(response) || response.body == null) {
            response.close()
            throw IneligibleAsset()
        }
        return storeResponse(key, response)
    }

    private fun localPresent(key: String): Boolean {
        val meta = File(store, "$key.meta")
        val body = File(store, "$key.bin")
        return meta.isFile && body.isFile && body.length() in 1..MAX_BYTES
    }

    /** Disk hits stream from the file — no multi‑MiB heap copy on every cold start. */
    private fun openLocal(key: String): Asset? {
        val meta = File(store, "$key.meta")
        val body = File(store, "$key.bin")
        if (!meta.isFile || !body.isFile) return null
        val size = body.length()
        if (size !in 1..MAX_BYTES) return null
        return runCatching {
            val lines = meta.readLines(Charsets.UTF_8)
            val mime = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
            val headers = linkedMapOf<String, String>()
            for (line in lines.drop(1)) {
                val at = line.indexOf(':')
                if (at <= 0) continue
                val name = line.substring(0, at)
                if (name.equals("Content-Length", true) || name.equals("Content-Encoding", true)) continue
                headers[name] = line.substring(at + 1)
            }
            headers["Content-Length"] = size.toString()
            Asset(mime, headers, body.inputStream().buffered(DEFAULT_BUFFER_SIZE), size.toInt())
        }.getOrNull()
    }

    private fun storeResponse(key: String, response: Response): Stored {
        val body = response.body!!
        val mime = response.header("Content-Type")!!.substringBefore(';').trim()
        val responseHeaders = response.headers.toMultimap().filterKeys {
            !it.equals("Content-Encoding", true) && !it.equals("Content-Length", true) && !it.equals("Set-Cookie", true)
        }.mapValues { it.value.joinToString(", ") }
        val bytes = try {
            body.bytes()
        } finally {
            response.close()
        }
        check(bytes.size.toLong() <= MAX_BYTES) { "Asset exceeds 32 MiB" }
        val tmpMeta = File(store, "$key.meta.tmp")
        val tmpBody = File(store, "$key.bin.tmp")
        val meta = File(store, "$key.meta")
        val bin = File(store, "$key.bin")
        tmpBody.outputStream().use { out -> out.write(bytes); out.fd.sync() }
        tmpMeta.writeText(buildString {
            append(mime)
            for ((name, value) in responseHeaders) {
                append('\n').append(name).append(':').append(value)
            }
        }, Charsets.UTF_8)
        // Drop published meta first so a crash cannot pair a new body with stale meta.
        if (meta.exists()) check(meta.delete())
        if (bin.exists()) check(bin.delete())
        check(tmpBody.renameTo(bin) || (bin.delete() && tmpBody.renameTo(bin)))
        check(tmpMeta.renameTo(meta) || (meta.delete() && tmpMeta.renameTo(meta)))
        diagnostic("asset_bytes=${bytes.size} local_store=true")
        return Stored(mime, responseHeaders, bytes)
    }

    fun close() {
        network.dispatcher.cancelAll()
        inflight.values.forEach { it.cancel(true) }
        inflight.clear()
    }

    companion object {
        const val ROOT = "immutable_assets"
        const val MAX_BYTES = 32L * 1024 * 1024
        fun root(cacheDir: File) = File(cacheDir, ROOT)
        fun clear(cacheDir: File) { root(cacheDir).deleteRecursively() }
        fun assetKey(url: String): String =
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun cacheableUrl(url: okhttp3.HttpUrl): Boolean {
            if (vitePluginBundle(url)) return true
            val path = url.encodedPath.lowercase()
            if (path.contains("??") || path.contains(',')) return false
            val name = path.substringAfterLast('/')
            return name.length in 1..128 && (name.endsWith(".js") || name.endsWith(".css"))
        }
        /** Vite concatenates many plugin clients as `/plugins/??a.js,b.js&rev=…` (rev pins the body). */
        fun vitePluginBundle(url: okhttp3.HttpUrl): Boolean {
            if (!url.encodedPath.equals("/plugins/", true) && !url.encodedPath.equals("/plugins", true)) return false
            val query = url.encodedQuery ?: return false
            // First `?` starts the query; Vite keeps a second `?` so encodedQuery begins with `?`.
            if (!query.startsWith('?')) return false
            val rev = url.queryParameter("rev")?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,128}")) } ?: return false
            return query.contains(".js") || query.contains(".css")
        }
        fun fingerprintedAsset(url: okhttp3.HttpUrl): Boolean {
            if (vitePluginBundle(url)) return true
            val name = url.encodedPath.substringAfterLast('/')
            return name.matches(Regex("""[\w.-]+-[A-Za-z0-9_-]{6,}\.(js|css)"""))
        }
        fun declaredBytes(response: Response): Long? {
            response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }?.let { return it }
            val body = response.body?.contentLength() ?: return null
            return body.takeIf { it >= 0 }
        }
        fun eligible(response: Response): Boolean {
            val mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            val directives = response.header("Cache-Control").orEmpty().lowercase().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val vary = response.headers.values("Vary").flatMap { it.lowercase().split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
            val bytes = declaredBytes(response)
            val bounded = bytes == null || bytes <= MAX_BYTES
            if (response.code != 200 || mime !in setOf("text/javascript", "application/javascript", "text/css")) return false
            if (response.headers.values("Set-Cookie").isNotEmpty()) return false
            if (vary.any { it != "accept-encoding" }) return false
            if (!bounded) return false
            if (directives.any { it == "no-store" || it == "private" }) return false
            if ("public" in directives && "immutable" in directives) return true
            // Production currently omits Cache-Control on fingerprinted /assets and rev-pinned plugin bundles.
            return directives.isEmpty() && fingerprintedAsset(response.request.url)
        }
    }
}
