package com.labteto.dshmobile.browser

import org.junit.Assert.*
import org.junit.Test

class LaunchCoverStateTest {
    @Test fun `beginLaunch shows brand cover`() {
        val cover = LaunchCoverState()
        cover.beginLaunch()
        assertEquals(LaunchSurface.Launch, cover.surface)
        assertTrue(cover.brandCover)
        assertTrue(cover.coverVisible)
        assertTrue(cover.timeoutArmed)
        assertFalse(cover.keepSplash)
    }

    @Test fun `ordinary document start after ready hides brand logo`() {
        val cover = LaunchCoverState()
        cover.beginLaunch()
        assertTrue(cover.onInteractive())
        cover.onVisualComplete(cover.browserId)
        assertFalse(cover.coverVisible)
        cover.onDocumentStarted()
        assertEquals(LaunchSurface.DocumentLoading, cover.surface)
        assertTrue(cover.coverVisible)
        assertFalse(cover.brandCover)
    }

    @Test fun `document start while launch cover is up keeps brand cover`() {
        val cover = LaunchCoverState()
        cover.beginLaunch()
        cover.onDocumentStarted()
        assertEquals(LaunchSurface.Launch, cover.surface)
        assertTrue(cover.brandCover)
    }

    @Test fun `stale visual callback from a previous browser does not uncover`() {
        val cover = LaunchCoverState()
        cover.beginLaunch()
        val first = cover.browserId
        assertTrue(cover.onInteractive())
        cover.beginLaunch()
        assertTrue(cover.onInteractive())
        cover.onVisualComplete(first)
        assertTrue(cover.coverVisible)
        cover.onVisualComplete(cover.browserId)
        assertTrue(cover.surfaceReady)
    }

    @Test fun `resume preserves pending visual until real callback`() {
        val cover = LaunchCoverState()
        cover.beginLaunch()
        assertTrue(cover.onInteractive())
        cover.onResume()
        assertFalse(cover.surfaceReady)
        assertTrue(cover.awaitVisual)
        cover.onVisualComplete(cover.browserId)
        assertTrue(cover.surfaceReady)
    }

    @Test fun `gate rejection uncovers immediately`() {
        val cover = LaunchCoverState()
        cover.beginLaunch()
        cover.onGateRejected()
        assertTrue(cover.surfaceReady)
        assertTrue(cover.failOpened)
        assertFalse(cover.timeoutArmed)
        assertFalse(cover.onInteractive())
        assertFalse(cover.awaitVisual)
    }

    @Test fun `display budget exceeded keeps overlay and allows later uncover`() {
        val cover = LaunchCoverState()
        cover.beginLaunch()
        cover.onDisplayBudgetExceeded(hadInternalCommit = false)
        assertEquals(LaunchSurface.Failed, cover.surface)
        assertTrue(cover.retryVisible)
        assertTrue(cover.networkError)
        assertFalse(cover.keepSplash)
        assertTrue(cover.onInteractive())
        cover.onVisualComplete(cover.browserId)
        assertTrue(cover.surfaceReady)
        assertFalse(cover.retryVisible)
    }

    @Test fun `display budget after commit uncovers committed content`() {
        val cover = LaunchCoverState()
        cover.beginLaunch()
        assertTrue(cover.onInteractive())
        assertTrue(cover.awaitVisual)
        cover.onDisplayBudgetExceeded(hadInternalCommit = true)
        assertTrue(cover.surfaceReady)
        assertFalse(cover.coverVisible)
        assertFalse(cover.retryVisible)
        assertFalse(cover.awaitVisual)
        assertNull(cover.lastFailure)
    }

    @Test fun `display budget after commit is ready not failed timeout`() {
        val cover = LaunchCoverState()
        cover.beginLaunch()
        cover.onDisplayBudgetExceeded(hadInternalCommit = true)
        assertTrue(cover.surfaceReady)
        assertFalse(cover.retryVisible)
        assertFalse(cover.networkError)
        assertNull(cover.lastFailure)
    }

    @Test fun `slow hint does not uncover`() {
        val cover = LaunchCoverState()
        cover.beginLaunch()
        cover.onSlowHint()
        assertTrue(cover.slowHint)
        assertTrue(cover.coverVisible)
        assertFalse(cover.retryVisible)
    }

    @Test fun `cold start drops system splash so brand title cover is visible`() {
        val cover = LaunchCoverState()
        assertTrue(cover.keepSplash)
        cover.beginLaunch()
        assertFalse(cover.keepSplash)
        assertTrue(cover.brandCover)
        assertTrue(cover.onInteractive())
        assertFalse(cover.keepSplash)
        cover.onVisualComplete(cover.browserId)
        assertFalse(cover.keepSplash)
        assertTrue(cover.surfaceReady)
    }

    @Test fun `restore without reload uncovers immediately`() {
        val cover = LaunchCoverState()
        cover.onRestoreWithoutReload()
        assertTrue(cover.surfaceReady)
        assertFalse(cover.keepSplash)
        assertFalse(cover.timeoutArmed)
    }

    @Test fun `network failure is immediate`() {
        val cover = LaunchCoverState()
        cover.beginLaunch()
        assertTrue(cover.onInteractive())
        cover.onVisualComplete(cover.browserId)
        cover.onDocumentStarted()
        cover.failed(LaunchFailure.Network)
        assertEquals(LaunchSurface.Failed, cover.surface)
        assertTrue(cover.networkError)
        assertTrue(cover.retryVisible)
    }
}
