package com.labteto.dshmobile.update

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionTest {
    @Test fun `encodes semver like gradle`() {
        assertEquals(1_202, AppVersion.codeFromName("0.12.2"))
        assertEquals(1_202, AppVersion.codeFromName("v0.12.2"))
        assertEquals(1_202, AppVersion.codeFromName("0.12.2-alpha.1"))
        assertEquals(1, AppVersion.codeFromName(""))
        assertEquals(10_000, AppVersion.codeFromName("1.0.0"))
    }

    @Test fun `normalizes tag`() {
        assertEquals("0.12.2", AppVersion.normalizeName("v0.12.2"))
        assertEquals("0.12.2", AppVersion.normalizeName(" 0.12.2 "))
    }
}
