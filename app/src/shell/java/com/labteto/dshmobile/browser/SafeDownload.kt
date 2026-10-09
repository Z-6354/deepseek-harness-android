package com.labteto.dshmobile.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Foreground, explicitly requested GET only. No disk queue, cookie jar, cache, or logging. */
class SafeDownload(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()) {
    @Volatile private var currentLease: DownloadLease? = null
    fun newLease(): DownloadLease = DownloadLease().also { currentLease?.cancel(); currentLease = it }
    fun cancel() { currentLease?.cancel() }

    suspend fun transfer(site: Site, initial: String, cookie: suspend (String) -> String?, owns: () -> Boolean, output: OutputStream, lease: DownloadLease = newLease()) {
        var next = initial
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        try { repeat(4) { attempt ->
            lease.ensureActive(owns)
            check(NavigationPolicy.decide(site, next) == Navigation.INTERNAL) { "Only same-origin HTTPS downloads are supported" }
            // Read briefly from the browser jar for this exact path; never retain in a queue.
            val header = cookie(next)
            lease.ensureActive(owns)
            val request = Request.Builder().url(next).get().apply {
                if (!header.isNullOrBlank()) header("Cookie", header)
            }.build()
            val active = client.newCall(request)
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Download timed out" }
            active.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            lease.publish(active, owns)
            try { withContext(Dispatchers.IO) { active.execute().use { response ->
                lease.ensureActive(owns)
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    check(attempt < 3) { "Too many redirects" }
                    next = next.toHttpUrl().resolve(response.header("Location") ?: error("Missing redirect"))?.toString() ?: error("Invalid redirect")
                    check(NavigationPolicy.decide(site, next) == Navigation.INTERNAL) { "Cross-origin download refused" }
                } else {
                    check(response.isSuccessful) { "Download failed" }
                    val body = response.body ?: error("Empty download")
                    val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                    check(mime !in setOf("text/html", "application/xhtml+xml", "application/javascript", "text/javascript")) { "The server returned a page, not a file" }
                    check(body.contentLength() <= MAX_BYTES) { "File exceeds 25 MiB" }
                        body.byteStream().use { input ->
                            val head = ByteArray(512)
                            var headCount = 0
                            while (headCount < head.size) {
                                val read = input.read(head, headCount, head.size - headCount)
                                if (read < 0) break
                                lease.ensureActive(owns)
                                headCount += read
                            }
                            val first = head.copyOf(headCount)
                            val prefix = first.toString(Charsets.UTF_8).trimStart().lowercase()
                            check(!prefix.startsWith("<!doctype html") && !prefix.startsWith("<html")) { "The server returned a login page" }
                            lease.write(owns) { output.write(first) }
                            val buffer = ByteArray(32768)
                            var count = first.size.toLong()
                            while (true) {
                                lease.ensureActive(owns)
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                check(count <= MAX_BYTES) { "File exceeds 25 MiB" }
                                lease.write(owns) { output.write(buffer, 0, read) }
                            }
                            lease.write(owns) { output.flush() }
                        }
                    return@withContext true
                }
                false
            } }.let { finished -> if (finished) return } } finally { lease.release(active) }
        }
        error("Too many redirects")
        } finally { lease.cancel() }
    }
    companion object { const val MAX_BYTES = 25L * 1024 * 1024 }
}
