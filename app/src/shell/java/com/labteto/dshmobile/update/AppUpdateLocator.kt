package com.labteto.dshmobile.update

/**
 * Prefer the operator-hosted manifest (reachable in China), then GitHub Releases on this fork.
 */
class AppUpdateLocator(
    private val hosted: HostedAppUpdateSource = HostedAppUpdateSource(),
    private val github: GitHubAppUpdateSource = GitHubAppUpdateSource(),
) {
    suspend fun latest(): AppUpdateOffer? = hosted.latest() ?: github.latest()
}
