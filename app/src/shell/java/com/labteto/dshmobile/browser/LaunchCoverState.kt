package com.labteto.dshmobile.browser

enum class LaunchSurface { Launch, DocumentLoading, Ready, Failed }

enum class LaunchFailure { Network, Certificate, Timeout, Storage }

/**
 * Launch cover presentation only (observer).
 * Document generation lives in [DocumentEpoch]; this type must not bump it.
 */
class LaunchCoverState {
    var launchId = 0L
        private set
    var browserId = 0L
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
        awaitVisual = false
        timeoutArmed = true
        networkError = false
        failOpened = false
        slowHint = false
        retryVisible = false
        lastFailure = null
        surface = LaunchSurface.Launch
        // Drop system splash (large icon) ASAP so the in-app spinner cover can take over.
        keepSplash = false
    }

    fun onBrowserRecreated() = beginLaunch()

    /** Ordinary navigations dim content; the initial launch cover keeps the brand. */
    fun onDocumentStarted() {
        awaitVisual = false
        networkError = false
        surface = when (surface) {
            LaunchSurface.Launch -> LaunchSurface.Launch
            LaunchSurface.Ready, LaunchSurface.DocumentLoading, LaunchSurface.Failed -> LaunchSurface.DocumentLoading
        }
    }

    /** @return false when the cover already uncovered via gate rejection. */
    fun onInteractive(): Boolean {
        if (failOpened && surface == LaunchSurface.Ready) return false
        awaitVisual = true
        return true
    }

    fun onVisualComplete(capturedBrowserId: Long) {
        if (capturedBrowserId != browserId || !awaitVisual) return
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

    /**
     * Display budget exceeded — presentation only. Does not stop the pipeline;
     * a later [onVisualComplete] (if pageReady already armed [awaitVisual]) or a later
     * [onInteractive] + [onVisualComplete] may still uncover.
     *
     * When the main frame already committed, lift the opaque cover so login/home
     * under it is usable (pageReady/visual may still be missing after auth-gate 200s).
     */
    fun onDisplayBudgetExceeded(hadInternalCommit: Boolean) {
        if (surface == LaunchSurface.Ready) return
        timeoutArmed = false
        keepSplash = false
        slowHint = false
        if (hadInternalCommit) {
            awaitVisual = false
            networkError = false
            failOpened = false
            retryVisible = false
            lastFailure = null
            surface = LaunchSurface.Ready
            return
        }
        // Keep awaitVisual when pageReady already requested a frame; clearing it permanently
        // blocked late VisualStateCallback after a slow interactive.
        lastFailure = LaunchFailure.Timeout
        retryVisible = true
        surface = LaunchSurface.Failed
        networkError = true
        failOpened = false
    }

    fun onDeadline() = onDisplayBudgetExceeded(hadInternalCommit = false)

    fun onTimeout() = onDeadline()

    fun failed(category: LaunchFailure, hadInternalCommit: Boolean = false) {
        if (category == LaunchFailure.Timeout) {
            onDisplayBudgetExceeded(hadInternalCommit)
            return
        }
        if (surface == LaunchSurface.Ready && category == LaunchFailure.Timeout) return
        timeoutArmed = false
        awaitVisual = false
        keepSplash = false
        lastFailure = category
        retryVisible = true
        slowHint = false
        surface = LaunchSurface.Failed
        networkError = category == LaunchFailure.Network
        failOpened = false
    }

    fun onGateRejected() {
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
        // Resuming does not prove a visual frame completed.
        if (surface == LaunchSurface.Ready) keepSplash = false
    }

    fun onRestoreWithoutReload() {
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
