package com.labteto.dshmobile.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WebImageTransferTest {
    private val limit = 25L * 1024 * 1024

    /** A scripted page: answers by which WebImageTransfer script it was handed. */
    private class FakePage(
        val bytes: ByteArray = ByteArray(0),
        var pendingPolls: Int = 0,
        var begin: String? = "\"started\"",
        var terminal: String = "ready",
        var claimedSize: Long? = null,
        var corruptChunks: Boolean = false,
        var shortChunks: Boolean = false,
    ) {
        val scripts = mutableListOf<String>()
        var released = false
        suspend fun eval(script: String): String? {
            scripts += script
            return when {
                script.contains("fetch(") -> begin
                script.contains("s.state+':'+s.size") -> if (pendingPolls-- > 0) "\"pending:0\"" else "\"$terminal:${claimedSize ?: bytes.size}\""
                script.contains("btoa(o)") -> {
                    val (from, to) = Regex("subarray\\((\\d+),(\\d+)\\)").find(script)!!.destructured
                    var slice = bytes.copyOfRange(from.toInt(), to.toInt())
                    if (shortChunks) slice = slice.copyOf(slice.size - 1)
                    "\"" + (if (corruptChunks) "!!not base64!!" else Base64.getEncoder().encodeToString(slice)) + "\""
                }
                script.contains("delete window") -> { released = true; "\"\"" }
                else -> null
            }
        }
    }

    private fun read(page: FakePage, max: Long = limit): Pair<Long, ByteArray> {
        val sink = java.io.ByteArrayOutputStream()
        val total = runBlocking { WebImageTransfer.readBlob(page::eval, "blob:https://site.test/x", "id1", max) { sink.write(it) } }
        return total to sink.toByteArray()
    }

    @Test fun blobBytesArriveIntactAcrossSeveralChunks() {
        val data = ByteArray(WebImageTransfer.CHUNK_BYTES * 2 + 123) { (it * 31).toByte() }
        val page = FakePage(bytes = data, pendingPolls = 2)
        val (total, out) = read(page)
        assertEquals(data.size.toLong(), total)
        assertArrayEquals(data, out)
        assertTrue("the page copy must be dropped after success", page.released)
        assertEquals("three slices for 2 chunks + remainder", 3, page.scripts.count { it.contains("btoa(o)") })
    }

    @Test fun noScriptEverUsesSynchronousXhrOrAWholeImageBase64() {
        val page = FakePage(bytes = ByteArray(10))
        read(page)
        assertTrue(page.scripts.none { it.contains("XMLHttpRequest") })
        assertTrue("an async fetch is used", page.scripts.first().contains("fetch("))
    }

    @Test fun anImageOverTheCapIsRefusedBeforeAnyChunkIsRequested() {
        val page = FakePage(terminal = "toolarge")
        val error = runCatching { read(page) }.exceptionOrNull()
        assertTrue(error is WebImageTransfer.TransferException)
        assertTrue(error!!.message!!.contains("25 MiB"))
        assertEquals(0, page.scripts.count { it.contains("btoa(o)") })
        assertTrue(page.released)
    }

    @Test fun aPageThatUnderstatesItsSizeCannotSneakPastTheCap() {
        // The page says 4 bytes but the claim is checked against the cap, and every chunk is length-checked.
        val page = FakePage(bytes = ByteArray(4096), claimedSize = limit + 1)
        assertTrue(runCatching { read(page) }.exceptionOrNull() is WebImageTransfer.TransferException)
        assertEquals(0, page.scripts.count { it.contains("btoa(o)") })
    }

    @Test fun aShortOrCorruptChunkIsRejectedNotWrittenAsAGoodFile() {
        assertTrue(runCatching { read(FakePage(bytes = ByteArray(100), shortChunks = true)) }.exceptionOrNull() is WebImageTransfer.TransferException)
        assertTrue(runCatching { read(FakePage(bytes = ByteArray(100), corruptChunks = true)) }.exceptionOrNull() is WebImageTransfer.TransferException)
    }

    @Test fun aFailedFetchAndAClosedDocumentBothFail() {
        assertTrue(runCatching { read(FakePage(terminal = "error")) }.exceptionOrNull() is WebImageTransfer.TransferException)
        assertTrue(runCatching { read(FakePage(begin = null)) }.exceptionOrNull() is WebImageTransfer.TransferException)
    }

    @Test fun cancellationStillAsksThePageToReleaseItsCopy(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val page = FakePage(bytes = ByteArray(10), pendingPolls = Int.MAX_VALUE)
        val job = async {
            WebImageTransfer.readBlob({ script -> if (script.contains("s.state+':'+s.size")) gate.await(); page.eval(script) }, "blob:x", "id1", limit) { }
        }
        kotlinx.coroutines.yield()
        job.cancel()
        runCatching { job.await() }
        assertTrue(job.isCancelled)
        assertTrue("page copy must be released even when cancelled", page.released)
        gate.complete(Unit)
    }

    @Test fun dataUrlsAreDecodedNativelyWithoutTouchingThePage() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val url = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        assertArrayEquals(png, WebImageTransfer.decodeDataUrl(url, limit))
        assertArrayEquals("a+b c".toByteArray(), WebImageTransfer.decodeDataUrl("data:text/plain,a+b%20c", limit))
    }

    @Test fun anOversizedDataUrlIsRefusedBeforeAllocating() {
        val huge = "data:image/png;base64," + "A".repeat(((limit / 3 + 10) * 4).toInt())
        val error = runCatching { WebImageTransfer.decodeDataUrl(huge, limit) }.exceptionOrNull()
        assertTrue(error is WebImageTransfer.TransferException)
        assertTrue(runCatching { WebImageTransfer.decodeDataUrl("data:image/png;base64,@@@@", limit) }.exceptionOrNull() is WebImageTransfer.TransferException)
        assertTrue(runCatching { WebImageTransfer.decodeDataUrl("data:image/png", limit) }.exceptionOrNull() is WebImageTransfer.TransferException)
    }

    @Test fun theSameLimitBacksTheDialogTheBlobPathAndTheHttpPath() {
        assertEquals(25L * 1024 * 1024, SafeDownload.MAX_BYTES)
        val source = java.io.File("src/shell/java/com/labteto/dshmobile/MainActivity.kt").readText()
        assertFalse("the 8 MiB blob cap must be gone", source.contains("8 * 1024 * 1024") || source.contains("8 MiB"))
        assertTrue(source.contains("SafeDownload.MAX_BYTES"))
    }

    @Test fun scriptsQuoteHostileUrlsAndIdsSoPageCodeCannotBeInjected() {
        val script = WebImageTransfer.beginScript("blob:x\");alert(1);(\"", "id\"", limit)
        assertFalse(script.contains("\");alert(1);(\""))
        assertTrue(script.contains("\\\");alert(1);(\\\""))
        assertEquals("a/b", WebImageTransfer.unquote("\"a\\/b\""))
        assertNull(WebImageTransfer.unquote("null"))
        assertNull(WebImageTransfer.unquote("\"a\\u0041\""))
    }
}
