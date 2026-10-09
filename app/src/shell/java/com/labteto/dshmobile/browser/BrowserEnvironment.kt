package com.labteto.dshmobile.browser

import android.webkit.WebStorage
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature

/** Process-wide ownership. Never assume destroying a view has stopped service worker writers. */
object BrowserEnvironment {
    var pageCreated = false
        private set
    private val coordinator = CleanupCoordinator()
    val isCleaning: Boolean get() = coordinator.isCleaning
    val transaction: CleanupTransaction? get() = coordinator.transaction
    fun markCreated() { coordinator.assertCanCreateWriter(); pageCreated = true }
    fun canDelete(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)

    fun detach(observerKey: String) = coordinator.detach(observerKey)
    fun clean(repository: SiteRepository, transaction: CleanupTransaction, observerKey: String, complete: (CleanupTransaction, Boolean) -> Unit) {
        if (pageCreated || !WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) { complete(transaction, false); return }
        val accepted = coordinator.attachOrStart(transaction, observerKey, delete = { done ->
            // This process has never loaded a page. The prior process was stopped before entry.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) && WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS)) {
                ServiceWorkerControllerCompat.getInstance().serviceWorkerWebSettings.blockNetworkLoads = true
            }
            // Public immutable assets remain on disk; replay still checks server authorization.
            WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) { done() }
        }, commit = { captured ->
            val committed = repository.completeCleanup(captured)
            if (committed && WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) && WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS)) {
                ServiceWorkerControllerCompat.getInstance().serviceWorkerWebSettings.blockNetworkLoads = false
            }
            committed
        }, complete = complete)
        if (!accepted) complete(transaction, false)
    }
}
