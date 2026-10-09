package com.labteto.dshmobile.browser

enum class LaunchSurface { Launch, DocumentLoading, Ready, Failed }

enum class LaunchFailure { Network, Certificate, Timeout, Storage }

/** Launch presentation is separate from document generation used to invalidate callbacks. */
class LaunchCoverState {
    @Volatile var generation = 0L
        private set
    var launchId = 0L
        private set
    var browserId = 0L
        private set
    var pendingPageReady = false
        private set
    var committedInternalUrl: String? = null
        private set
    var navigatingBack = false
        private set
    var surface = LaunchSurface.Launch
        private set
    val coverVisible get() = surface != LaunchSurface.Ready
    val brandCover get() = surface == LaunchSurface.Launch
    val surfaceReady get() = surface == LaunchSurface.Ready
    var keepSplash = true
        private set
    var awaitVisual = false
        private set
    var timeoutArmed = true
        private set
    var networkError = false
        private set
    var sawInternalCommit = false
        private set
    var failOpened = false
        private set
    var slowHint = false
        private set
    var retryVisible = false
        private set
    var lastFailure: LaunchFailure? = null
        private set

    fun beginLaunch() {
        launchId++
        browserId++
        pendingPageReady = false
        committedInternalUrl = null
        awaitVisual = false
        timeoutArmed = true
        networkError = false
        sawInternalCommit = false
        failOpened = false
        slowHint = false
        retryVisible = false
        lastFailure = null
        surface = LaunchSurface.Launch
        // Keep the system splash up with the brand cover so cold start is one
        // continuous loading stage instead of splash → whale → page.
        keepSplash = true
    }

    fun onBrowserRecreated() = beginLaunch()

    fun onGoBack() {
        navigatingBack = true
    }

    fun documentStarted() {
        val back = navigatingBack
        navigatingBack = false
        generation++
        pendingPageReady = false
        committedInternalUrl = null
        awaitVisual = false
        networkError = false
        sawInternalCommit = false
        if (back) return
        surface = when (surface) {
            LaunchSurface.Launch -> LaunchSurface.Launch
            LaunchSurface.Ready, LaunchSurface.DocumentLoading, LaunchSurface.Failed -> LaunchSurface.DocumentLoading
        }
    }

    fun onMainFrameStarted() = documentStarted()

    fun onInternalCommit(url: String) = documentCommitted(generation, url)

    fun documentCommitted(epoch: Long, url: String) {
        if (epoch != generation) return
        committedInternalUrl = url
        sawInternalCommit = true
        if (pendingPageReady) {
            pendingPageReady = false
            awaitVisual = true
        }
    }

    fun onNonInternalCommit() {
        // Stay covered; only Ready / Failed ends the single loading stage.
    }

    fun onPageReady(): Boolean = pageReady(generation)

    fun pageReady(epoch: Long): Boolean {
        if (epoch != generation) return false
        if (failOpened && surface == LaunchSurface.Ready) {
            pendingPageReady = false
            return false
        }
        if (committedInternalUrl != null) {
            pendingPageReady = false
            awaitVisual = true
            return true
        }
        pendingPageReady = true
        return false
    }

    fun onVisualComplete(epoch: Long) = onVisualComplete(browserId, epoch)

    fun onVisualComplete(capturedBrowserId: Long, epoch: Long) {
        if (capturedBrowserId != browserId || epoch != generation || !awaitVisual) return
        awaitVisual = false
        timeoutArmed = false
        networkError = false
        slowHint = false
        retryVisible = false
        lastFailure = null
        failOpened = false
        surface = LaunchSurface.Ready
        keepSplash = false
    }

    fun onSlowHint() {
        if (!timeoutArmed || surface == LaunchSurface.Ready) return
        slowHint = true
    }

    fun onDeadline() = failed(browserId, generation, LaunchFailure.Timeout)

    fun onTimeout() = onDeadline()

    fun failed(capturedBrowserId: Long, epoch: Long, category: LaunchFailure) {
        if (capturedBrowserId != browserId || epoch != generation) return
        if (surface == LaunchSurface.Ready && category == LaunchFailure.Timeout) return
        timeoutArmed = false
        awaitVisual = false
        keepSplash = false
        lastFailure = category
        retryVisible = true
        slowHint = false
        surface = LaunchSurface.Failed
        networkError = category == LaunchFailure.Network || (category == LaunchFailure.Timeout && committedInternalUrl == null && !sawInternalCommit)
        failOpened = false
    }

    fun onGateRejected() {
        pendingPageReady = false
        awaitVisual = false
        timeoutArmed = false
        networkError = false
        failOpened = true
        slowHint = false
        retryVisible = false
        lastFailure = null
        surface = LaunchSurface.Ready
        keepSplash = false
    }

    fun onResume() {
        if (awaitVisual) {
            onVisualComplete(browserId, generation)
            return
        }
        if (surface == LaunchSurface.Ready) keepSplash = false
    }

    fun onRestoreWithoutReload() {
        pendingPageReady = false
        awaitVisual = false
        timeoutArmed = false
        networkError = false
        failOpened = false
        slowHint = false
        retryVisible = false
        lastFailure = null
        surface = LaunchSurface.Ready
        keepSplash = false
    }

    fun releaseSystemSplash() {
        keepSplash = false
    }
}
