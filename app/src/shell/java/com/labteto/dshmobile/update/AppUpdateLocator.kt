package com.labteto.dshmobile.update

import kotlin.coroutines.cancellation.CancellationException

/**
 * Prefer the operator-hosted manifest (reachable in China), then GitHub Releases on this fork.
 *
 * A source that throws (timeout, DNS, TLS, malformed manifest) counts as "no offer" so the next source
 * is still tried; previously only an HTTP-level null fell through to the backup.
 */
class AppUpdateLocator internal constructor(private val sources: List<suspend () -> AppUpdateOffer?>) {
    // GitHub holds source only: APKs are distributed from the operator's own server, never from Releases.
    constructor(
        hosted: HostedAppUpdateSource = HostedAppUpdateSource(),
    ) : this(listOf({ hosted.latest() }))

    suspend fun latest(): AppUpdateOffer? {
        for (source in sources) {
            val offer = try {
                source()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (offer != null) return offer
        }
        return null
    }
}
