package com.labteto.dshmobile.browser

import org.junit.Assert.*
import org.junit.Test

class WebCompatibilityTest {
    @Test fun `session seed script is empty for blank or unsafe ids`() {
        assertEquals("", WebCompatibility.seedSessionScript(null))
        assertEquals("", WebCompatibility.seedSessionScript(""))
        assertEquals("", WebCompatibility.seedSessionScript("bad id with spaces"))
        assertEquals("", WebCompatibility.seedSessionScript("<script>"))
    }

    @Test fun `session seed script quotes a safe id for document-start localStorage`() {
        val script = WebCompatibility.seedSessionScript("sess_abc-1.2:3")
        assertTrue(script.contains("dsh.sessions.current"))
        assertTrue(script.contains("dsh-mobile-hanui.last-session"))
        assertTrue(script.contains("sess_abc-1.2:3"))
        assertFalse(script.contains("<script>"))
    }
}
