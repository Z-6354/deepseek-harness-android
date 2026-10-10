package com.labteto.dshmobile.browser

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.Base64

/**
 * Moves the bytes of a `blob:` / `data:` image out of the page for "save image".
 *
 * `data:` URLs never enter the page: they are decoded here. `blob:` URLs only exist in the page, so the
 * page fetches the blob asynchronously (no synchronous XHR, no UI-thread stall), keeps it as a
 * `Uint8Array`, and native pulls it in [CHUNK_BYTES] slices. Each slice is written straight to the sink,
 * so neither side ever holds a 25 MiB base64 string or a second full copy.
 *
 * The page is untrusted: every reply is bounds-checked here, and the total is capped at [maxBytes]
 * independently of what the page claims.
 */
internal object WebImageTransfer {
    const val CHUNK_BYTES = 512 * 1024
    private const val POLL_DELAY_MS = 100L
    private const val POLL_ATTEMPTS = 100 // ~10 s for the page to finish reading the blob
    private const val SLOT = "__dshaSave"

    class TransferException(message: String) : Exception(message)

    /** One page-side slot per transfer; the random [id] keeps a stale script from touching a newer one. */
    fun beginScript(url: String, id: String, maxBytes: Long): String = """
        (function(){try{
          var s={id:${quote(id)},state:'pending',size:0,bytes:null};window.$SLOT=s;
          fetch(${quote(url)}).then(function(r){if(!r.ok&&r.status!==0)throw 0;return r.arrayBuffer()})
            .then(function(b){if(b.byteLength>$maxBytes){s.state='toolarge';return}
              s.bytes=new Uint8Array(b);s.size=b.byteLength;s.state='ready'})
            .catch(function(){s.state='error'});
          return 'started'}catch(e){return 'error'}})()
    """.trimIndent()

    fun statusScript(id: String): String =
        "(function(){var s=window.$SLOT;return s&&s.id===${quote(id)}?s.state+':'+s.size:'none'})()"

    fun chunkScript(id: String, from: Long, to: Long): String = """
        (function(){var s=window.$SLOT;if(!s||s.id!==${quote(id)}||s.state!=='ready')return '';
          var a=s.bytes.subarray($from,$to),o='';
          for(var i=0;i<a.length;i+=8192)o+=String.fromCharCode.apply(null,a.subarray(i,Math.min(i+8192,a.length)));
          return btoa(o)})()
    """.trimIndent()

    fun releaseScript(id: String): String =
        "(function(){var s=window.$SLOT;if(s&&s.id===${quote(id)})delete window.$SLOT;return ''})()"

    /**
     * Streams a `blob:` image into [sink]. [eval] runs a script in the owning document and returns the raw
     * `evaluateJavascript` result (a JSON-quoted string), or null when the document is gone.
     * Always asks the page to drop its copy before returning, success or not.
     */
    suspend fun readBlob(
        eval: suspend (String) -> String?,
        url: String,
        id: String,
        maxBytes: Long,
        sink: (ByteArray) -> Unit,
    ): Long {
        try {
            if (unquote(eval(beginScript(url, id, maxBytes))) != "started") throw TransferException("Image unavailable")
            var size = -1L
            for (attempt in 0 until POLL_ATTEMPTS) {
                val state = unquote(eval(statusScript(id))) ?: throw TransferException("Document closed")
                val state0 = state.substringBefore(':')
                when (state0) {
                    "ready" -> { size = state.substringAfter(':').toLongOrNull() ?: -1; break }
                    "toolarge" -> throw TransferException("File exceeds ${maxBytes / (1024 * 1024)} MiB")
                    "pending" -> delay(POLL_DELAY_MS)
                    else -> throw TransferException("Image unavailable")
                }
            }
            if (size < 0) throw TransferException("Image read timed out")
            // The page's claimed size is only a hint; the cap below is enforced on bytes actually received.
            if (size > maxBytes) throw TransferException("File exceeds ${maxBytes / (1024 * 1024)} MiB")
            var offset = 0L
            while (offset < size) {
                val end = minOf(offset + CHUNK_BYTES, size)
                val encoded = unquote(eval(chunkScript(id, offset, end))) ?: throw TransferException("Document closed")
                val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw TransferException("Corrupt image data") }
                if (bytes.size.toLong() != end - offset) throw TransferException("Corrupt image data")
                if (offset + bytes.size > maxBytes) throw TransferException("File exceeds ${maxBytes / (1024 * 1024)} MiB")
                sink(bytes)
                offset = end
            }
            return offset
        } finally {
            // Cancellation must still ask the page to drop its copy of the image.
            withContext(NonCancellable) { runCatching { eval(releaseScript(id)) } }
        }
    }

    /** Decodes a `data:` URL natively, refusing anything larger than [maxBytes] before allocating. */
    fun decodeDataUrl(url: String, maxBytes: Long): ByteArray {
        if (!url.startsWith("data:", ignoreCase = true)) throw TransferException("Not a data URL")
        val comma = url.indexOf(',')
        if (comma < 0) throw TransferException("Malformed data URL")
        val header = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        return if (header.split(';').any { it.equals("base64", ignoreCase = true) }) {
            val compact = payload.filterNot { it.isWhitespace() }
            val padding = compact.takeLastWhile { it == '=' }.length
            val decoded = compact.length.toLong() / 4 * 3 - padding
            if (decoded > maxBytes) throw TransferException("File exceeds ${maxBytes / (1024 * 1024)} MiB")
            // Strict decoder: the MIME one silently drops illegal characters and would save a truncated file.
            try { Base64.getDecoder().decode(compact) } catch (_: IllegalArgumentException) { throw TransferException("Corrupt image data") }
        } else {
            if (payload.length.toLong() > maxBytes * 3) throw TransferException("File exceeds ${maxBytes / (1024 * 1024)} MiB")
            // URLDecoder maps '+' to a space; in a data URL '+' is a literal plus.
            val bytes = try { URLDecoder.decode(payload.replace("+", "%2B"), "ISO-8859-1").toByteArray(Charsets.ISO_8859_1) }
            catch (_: IllegalArgumentException) { throw TransferException("Corrupt image data") }
            if (bytes.size > maxBytes) throw TransferException("File exceeds ${maxBytes / (1024 * 1024)} MiB")
            bytes
        }
    }

    /** `evaluateJavascript` hands back JSON; our replies are plain strings with no escapes. */
    internal fun unquote(raw: String?): String? {
        if (raw == null || raw == "null") return null
        if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') return null
        val body = raw.substring(1, raw.length - 1).replace("\\/", "/")
        return if (body.contains('\\')) null else body
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\u2028' -> append("\\u2028")
            c == '\u2029' -> append("\\u2029")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
