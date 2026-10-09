package com.labteto.dshmobile.browser

/** Idempotent disk half of the preferences cleanup transaction. */
internal class PrivateCleanupFence(private val store: PrivateFileStore) {
    fun prepare(transaction: CleanupTransaction): Boolean {
        store.beginCleanup(transaction.nonce)
        return store.deleteForCleanup(transaction.nonce)
    }

    fun finish(transaction: CleanupTransaction): Boolean {
        return store.finishCleanup(transaction.nonce)
    }
}
