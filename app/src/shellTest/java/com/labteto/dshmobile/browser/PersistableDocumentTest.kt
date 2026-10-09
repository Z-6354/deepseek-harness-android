package com.labteto.dshmobile.browser

import org.junit.Assert.*
import org.junit.Test

class PersistableDocumentTest {
    private fun persist(url: String) = SiteRepository.persistableDocument(url)

    @Test fun `query and fragment are never persisted`() {
        assertEquals("https://dsh.example.test/chat/abc", persist("https://dsh.example.test/chat/abc?token=SECRET&x=1#frag"))
        assertEquals("https://dsh.example.test/", persist("https://dsh.example.test/?login=one-time-token"))
        assertEquals("https://dsh.example.test:8443/a%20b", persist("https://dsh.example.test:8443/a%20b?q=1"))
    }

    @Test fun `empty path becomes root and plain paths are unchanged`() {
        assertEquals("https://dsh.example.test/", persist("https://dsh.example.test"))
        assertEquals("https://dsh.example.test/x/y", persist("https://dsh.example.test/x/y"))
    }

    @Test fun `credentials in the authority and unparsable input are refused`() {
        assertNull(persist("https://user:pass@dsh.example.test/"))
        assertNull(persist("not a url"))
        assertNull(persist("/relative/path"))
    }

    @Test fun `no secret from the input can survive in the output`() {
        val out = persist("https://dsh.example.test/p?token=SECRET#SECRET2")!!
        assertFalse(out.contains("SECRET"))
        assertFalse(out.contains('?')); assertFalse(out.contains('#'))
    }
}
