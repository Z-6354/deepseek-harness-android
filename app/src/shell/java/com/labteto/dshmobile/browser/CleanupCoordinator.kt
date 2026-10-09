package com.labteto.dshmobile.browser

data class CleanupTransaction(val nonce: String, val targetOwner: String?, val storageEpoch: String)

/** Process-owned deletion fence. A callback commits only its immutable transaction. */
class CleanupCoordinator {
    private data class Observer(val key: String, val complete: (CleanupTransaction, Boolean) -> Unit)
    private var active: CleanupTransaction? = null
    private var observer: Observer? = null
    val isCleaning: Boolean get() = active != null
    val transaction: CleanupTransaction? get() = active

    fun attachOrStart(
        transaction: CleanupTransaction,
        observerKey: String,
        delete: ((() -> Unit) -> Unit),
        commit: (CleanupTransaction) -> Boolean,
        complete: (CleanupTransaction, Boolean) -> Unit,
    ): Boolean {
        if (active != null && active != transaction) return false
        observer = Observer(observerKey, complete)
        if (active != null) return true
        active = transaction
        try {
            delete {
                if (active == transaction) {
                    val success = runCatching { commit(transaction) }.getOrDefault(false)
                    val latest = observer
                    active = null
                    observer = null
                    latest?.complete?.invoke(transaction, success)
                }
            }
        } catch (_: Exception) {
            if (active == transaction) {
                val latest = observer
                active = null; observer = null
                latest?.complete?.invoke(transaction, false)
            }
        }
        return true
    }

    fun detach(observerKey: String) { if (observer?.key == observerKey) observer = null }
    fun assertCanCreateWriter() { check(!isCleaning) { "Browser cleanup is still running" } }
}
