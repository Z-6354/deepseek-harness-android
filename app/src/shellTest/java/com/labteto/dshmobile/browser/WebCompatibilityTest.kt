package com.labteto.dshmobile.browser

import org.junit.Assert.*
import org.junit.Test

class WebCompatibilityTest {
    @Test fun `platform compatibility contains no website chat seed or storage mirror`() {
        val source = java.io.File("src/shell/java/com/labteto/dshmobile/browser/WebCompatibility.kt").readText()
        assertFalse(source.contains("localStorage"))
        assertFalse(source.contains("sessionId"))
        assertTrue(source.contains("addDocumentStartJavaScript"))
        assertTrue(source.contains("API_PROBE"))
        assertFalse(source.contains("evaluateJavascript"))
        val activity = java.io.File("src/shell/java/com/labteto/dshmobile/MainActivity.kt").readText()
        assertFalse(activity.contains("dsh.sessions.current"))
        assertFalse(activity.contains("dsh-mobile-hanui.last-session"))
        assertFalse(activity.contains("persistSessionFromWebView"))
    }
}
