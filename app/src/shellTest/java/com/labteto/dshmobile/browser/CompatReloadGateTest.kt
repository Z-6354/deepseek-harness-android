package com.labteto.dshmobile.browser

import org.junit.Assert.*
import org.junit.Test

class CompatReloadGateTest {
    @Test fun `reload only when apis missing and not yet issued`() {
        assertTrue(CompatReloadGate.shouldIssueReload(apisPresent = false, alreadyIssued = false))
        assertFalse(CompatReloadGate.shouldIssueReload(apisPresent = true, alreadyIssued = false))
        assertFalse(CompatReloadGate.shouldIssueReload(apisPresent = false, alreadyIssued = true))
        assertFalse(CompatReloadGate.shouldIssueReload(apisPresent = true, alreadyIssued = true))
    }
}
