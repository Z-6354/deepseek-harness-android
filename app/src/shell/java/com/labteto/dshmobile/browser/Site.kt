package com.labteto.dshmobile.browser

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** Website metadata only. Neither authentication nor business protocol lives here. */
@Serializable
data class Site(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val entryUrl: String,
    val revision: String = UUID.randomUUID().toString(),
    val allowNotifications: Boolean = true,
    val staticResourcePrefixes: List<String> = emptyList(),
) {
    val origin: String get() = NavigationPolicy.origin(entryUrl) ?: error("Invalid site")
    val owner: String get() = "$id|$revision|$entryUrl"
}

enum class Navigation { INTERNAL, EXTERNAL, BLOCKED }

object NavigationPolicy {
    fun normalizedEntry(raw: String): String? {
        val url = safeUrl(raw.trim()) ?: return null
        if (url.query != null || url.fragment != null) return null
        return url.toString()
    }

    private fun safeUrl(raw: String): okhttp3.HttpUrl? {
        if (raw.length > 2048 || raw.any { it.isISOControl() } || '\\' in raw) return null
        return raw.toHttpUrlOrNull()?.takeIf {
            it.scheme == "https" && it.username.isEmpty() && it.password.isEmpty()
        }
    }

    fun origin(raw: String): String? = safeUrl(raw)?.let {
        val host = if (':' in it.host) "[${it.host}]" else it.host
        "https://$host" + if (it.port == 443) "" else ":${it.port}"
    }

    fun decide(site: Site, raw: String): Navigation = when (origin(raw)) {
        null -> Navigation.BLOCKED
        site.origin -> Navigation.INTERNAL
        else -> Navigation.EXTERNAL
    }

    fun notificationTarget(site: Site, raw: String?): String? =
        if (raw == null) site.entryUrl else raw.takeIf { decide(site, it) == Navigation.INTERNAL }
}

/** Stable tap identity so two tags cannot share one PendingIntent. */
object WebsiteNotification {
    fun requestCode(owner: String, tag: String?): Int = (owner + '\u0000' + (tag ?: "website")).hashCode()
    fun tapUri(owner: String, tag: String?): String {
        val label = tag ?: "website"
        if (owner.any { it.isISOControl() } || label.any { it.isISOControl() }) return "hanapp-notification://open/invalid"
        val encodedOwner = java.net.URLEncoder.encode(owner, Charsets.UTF_8.name())
        val encodedTag = java.net.URLEncoder.encode(label, Charsets.UTF_8.name())
        return "hanapp-notification://open/$encodedOwner/$encodedTag"
    }
}

enum class EnvironmentDecision { REUSE, CLEAN, RESTART, UNSUPPORTED }

object EnvironmentPolicy {
    fun decide(owner: String?, target: String?, pending: Boolean, pageCreated: Boolean, canDelete: Boolean): EnvironmentDecision {
        if (!pending && owner == target && owner != null) return EnvironmentDecision.REUSE
        if (!canDelete) return EnvironmentDecision.UNSUPPORTED
        return if (pageCreated) EnvironmentDecision.RESTART else EnvironmentDecision.CLEAN
    }
}
