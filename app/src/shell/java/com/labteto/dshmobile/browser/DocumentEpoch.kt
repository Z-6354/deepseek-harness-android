package com.labteto.dshmobile.browser

/** Document generation and commit — content pipeline, not cover presentation. */
enum class InteractiveDisposition { Ignored, PendingCommit, ReadyForVisual }

data class DocumentLease(val browserInstance: Long, val documentGeneration: Long)

class DocumentEpoch {
    @Volatile private var browserInstance = 0L
    @Volatile private var active = false
    @Volatile private var initialConsumerGeneration = 0L
    fun beginBrowser() { browserInstance++; active = true; initialConsumerGeneration = generation; clearForNewLaunch() }
    fun retireBrowser() { active = false }
    fun lease() = DocumentLease(browserInstance, generation)
    fun owns(lease: DocumentLease) = active && lease.browserInstance == browserInstance && lease.documentGeneration == generation
    /** A callback captured before the first onPageStarted belongs to that same first document. */
    fun ownsInitialConsumer(lease: DocumentLease) = owns(lease) ||
        (active && lease.browserInstance == browserInstance && lease.documentGeneration == initialConsumerGeneration && generation == initialConsumerGeneration + 1)

    @Volatile var generation = 0L
        private set
    var committedInternalUrl: String? = null
        private set
    var pendingPageReady = false
        private set
    var navigatingBack = false
        private set
    var sawInternalCommit = false
        private set

    fun onGoBack() {
        navigatingBack = true
    }

    /** @return true when this start is a back navigation (cover should not re-brand). */
    fun documentStarted(): Boolean {
        val back = navigatingBack
        navigatingBack = false
        generation++
        pendingPageReady = false
        committedInternalUrl = null
        sawInternalCommit = false
        return back
    }

    fun documentCommitted(epoch: Long, url: String): Boolean {
        if (epoch != generation) return false
        committedInternalUrl = url
        sawInternalCommit = true
        return true
    }

    fun onInternalCommit(url: String) = documentCommitted(generation, url)

    fun onInteractive(epoch: Long): InteractiveDisposition {
        if (epoch != generation) return InteractiveDisposition.Ignored
        if (committedInternalUrl != null) {
            pendingPageReady = false
            return InteractiveDisposition.ReadyForVisual
        }
        pendingPageReady = true
        return InteractiveDisposition.PendingCommit
    }

    /** After a late commit, consume a pageReady that arrived earlier. */
    fun consumePendingInteractive(): Boolean {
        if (!pendingPageReady || committedInternalUrl == null) return false
        pendingPageReady = false
        return true
    }

    fun clearForNewLaunch() {
        pendingPageReady = false
        committedInternalUrl = null
        sawInternalCommit = false
        navigatingBack = false
    }
}
