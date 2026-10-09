package com.labteto.dshmobile.browser

import org.junit.Assert.*
import org.junit.Test

class LaunchCoverStateTest {
    @Test fun `beginLaunch shows brand cover and is independent of generation`() {
        val state = LaunchCoverState()
        state.documentStarted()
        assertEquals(1L, state.generation)
        state.onInternalCommit("https://example.test/")
        state.onPageReady()
        state.onVisualComplete(state.browserId, state.generation)
        state.beginLaunch()
        assertEquals(1L, state.generation)
        assertEquals(LaunchSurface.Launch, state.surface)
        assertTrue(state.brandCover)
        assertTrue(state.coverVisible)
        assertFalse(state.surfaceReady)
        assertTrue(state.timeoutArmed)
    }

    @Test fun `ordinary document start after ready hides old content without brand logo`() {
        val state = LaunchCoverState()
        state.beginLaunch()
        state.documentStarted()
        state.onInternalCommit("https://example.test/")
        assertTrue(state.onPageReady())
        state.onVisualComplete(state.browserId, state.generation)
        assertFalse(state.coverVisible)
        state.documentStarted()
        assertEquals(LaunchSurface.DocumentLoading, state.surface)
        assertTrue(state.coverVisible)
        assertFalse(state.brandCover)
        assertFalse(state.surfaceReady)
        assertNull(state.committedInternalUrl)
    }

    @Test fun `login replace while the launch cover is still up keeps the brand cover`() {
        val state = LaunchCoverState()
        state.beginLaunch()
        state.documentStarted()
        state.onPageReady()
        state.documentStarted()
        assertEquals(LaunchSurface.Launch, state.surface)
        assertTrue(state.brandCover)
        assertFalse(state.pendingPageReady)
        state.onInternalCommit("https://example.test/")
        assertFalse(state.awaitVisual)
        state.onVisualComplete(state.browserId, 1L)
        assertTrue(state.coverVisible)
        state.onPageReady()
        state.onVisualComplete(state.browserId, state.generation)
        assertTrue(state.surfaceReady)
    }

    @Test fun `stale visual callback from a previous browser does not uncover`() {
        val state = LaunchCoverState()
        state.beginLaunch()
        val firstBrowser = state.browserId
        state.documentStarted()
        state.onInternalCommit("https://example.test/")
        assertTrue(state.onPageReady())
        state.beginLaunch()
        state.documentStarted()
        state.onInternalCommit("https://example.test/app")
        assertTrue(state.onPageReady())
        state.onVisualComplete(firstBrowser, state.generation)
        assertTrue(state.coverVisible)
        state.onVisualComplete(state.browserId, state.generation)
        assertTrue(state.surfaceReady)
    }

    @Test fun `goBack invalidates callbacks without covering again`() {
        val state = LaunchCoverState()
        state.beginLaunch()
        state.documentStarted()
        state.onInternalCommit("https://example.test/login")
        state.onPageReady()
        state.onVisualComplete(state.browserId, state.generation)
        state.onGoBack()
        state.documentStarted()
        assertFalse(state.coverVisible)
        assertTrue(state.surfaceReady)
        assertEquals(2L, state.generation)
        assertNull(state.committedInternalUrl)
    }

    @Test fun `resume does not relaunch when already ready`() {
        val state = LaunchCoverState()
        state.beginLaunch()
        state.documentStarted()
        state.onInternalCommit("https://example.test/")
        state.onPageReady()
        state.onVisualComplete(state.browserId, state.generation)
        state.onResume()
        assertFalse(state.coverVisible)
        assertTrue(state.surfaceReady)
    }

    @Test fun `resume completes a stuck visual wait using the captured generation`() {
        val state = LaunchCoverState()
        state.beginLaunch()
        state.documentStarted()
        state.onInternalCommit("https://example.test/")
        assertTrue(state.onPageReady())
        state.onResume()
        assertFalse(state.coverVisible)
        assertTrue(state.surfaceReady)
        assertFalse(state.awaitVisual)
    }

    @Test fun `gate rejection uncovers immediately without waiting for pageReady`() {
        val state = LaunchCoverState()
        state.beginLaunch()
        state.documentStarted()
        state.onInternalCommit("https://example.test/")
        assertTrue(state.coverVisible)
        state.onGateRejected()
        assertFalse(state.coverVisible)
        assertTrue(state.surfaceReady)
        assertTrue(state.failOpened)
        assertFalse(state.timeoutArmed)
        assertFalse(state.onPageReady())
    }

    @Test fun `deadline after a later document start does not succeed from an older commit`() {
        val state = LaunchCoverState()
        state.beginLaunch()
        state.documentStarted()
        state.onInternalCommit("https://example.test/")
        state.documentStarted()
        assertTrue(state.coverVisible)
        assertNull(state.committedInternalUrl)
        state.onDeadline()
        assertEquals(LaunchSurface.Failed, state.surface)
        assertTrue(state.retryVisible)
        assertFalse(state.surfaceReady)
        assertTrue(state.networkError)
    }

    @Test fun `slow hint does not uncover or extend generation`() {
        val state = LaunchCoverState()
        state.beginLaunch()
        state.documentStarted()
        val generation = state.generation
        state.onSlowHint()
        assertTrue(state.slowHint)
        assertTrue(state.coverVisible)
        assertFalse(state.retryVisible)
        assertEquals(generation, state.generation)
    }

    @Test fun `timeout without document reports network error and keeps the overlay`() {
        val state = LaunchCoverState()
        state.beginLaunch()
        state.documentStarted()
        state.onTimeout()
        assertTrue(state.coverVisible)
        assertFalse(state.surfaceReady)
        assertTrue(state.networkError)
        assertTrue(state.retryVisible)
        assertFalse(state.keepSplash)
    }

    @Test fun `blank commit stays covered as one loading stage`() {
        val state = LaunchCoverState()
        state.beginLaunch()
        state.documentStarted()
        state.onNonInternalCommit()
        assertTrue(state.keepSplash)
        assertTrue(state.coverVisible)
        assertNull(state.committedInternalUrl)
    }

    @Test fun `cold start keeps system splash until ready so entry is one stage`() {
        val state = LaunchCoverState()
        assertTrue(state.keepSplash)
        state.beginLaunch()
        assertTrue(state.keepSplash)
        state.documentStarted()
        state.onInternalCommit("https://example.test/")
        assertTrue(state.keepSplash)
        assertTrue(state.coverVisible)
        assertTrue(state.onPageReady())
        assertTrue(state.keepSplash)
        state.onVisualComplete(state.browserId, state.generation)
        assertFalse(state.keepSplash)
        assertFalse(state.coverVisible)
        assertTrue(state.surfaceReady)
    }

    @Test fun `restore without reload uncovers immediately`() {
        val state = LaunchCoverState()
        state.onRestoreWithoutReload()
        assertFalse(state.coverVisible)
        assertTrue(state.surfaceReady)
        assertFalse(state.keepSplash)
        assertFalse(state.timeoutArmed)
    }

    @Test fun `system splash can drop while the overlay still covers`() {
        val state = LaunchCoverState()
        assertTrue(state.keepSplash)
        assertTrue(state.coverVisible)
        state.releaseSystemSplash()
        assertFalse(state.keepSplash)
        assertTrue(state.coverVisible)
    }

    @Test fun `recreating the browser begins a new launch without bumping generation`() {
        val state = LaunchCoverState()
        state.beginLaunch()
        state.documentStarted()
        state.onInternalCommit("https://example.test/")
        state.onPageReady()
        state.onVisualComplete(state.browserId, state.generation)
        val generation = state.generation
        state.onBrowserRecreated()
        assertEquals(generation, state.generation)
        assertTrue(state.brandCover)
        assertFalse(state.surfaceReady)
        assertNull(state.committedInternalUrl)
        assertFalse(state.pendingPageReady)
    }

    @Test fun `network failure is immediate and does not use a previous success`() {
        val state = LaunchCoverState()
        state.beginLaunch()
        state.documentStarted()
        state.onInternalCommit("https://example.test/")
        state.onPageReady()
        state.onVisualComplete(state.browserId, state.generation)
        state.documentStarted()
        state.failed(state.browserId, state.generation, LaunchFailure.Network)
        assertEquals(LaunchSurface.Failed, state.surface)
        assertTrue(state.networkError)
        assertTrue(state.retryVisible)
        assertFalse(state.surfaceReady)
    }
}
