package com.labteto.dshmobile.browser

/**
 * After HyperOS-safe deferred [WebCompatibility.install], the first document never saw
 * document-start. One same-origin reload is enough for subsequent navigations to pick up the
 * script; never loop.
 */
object CompatReloadGate {
    fun shouldIssueReload(apisPresent: Boolean, alreadyIssued: Boolean): Boolean =
        !alreadyIssued && !apisPresent
}
