package com.labteto.dshmobile.browser

import kotlinx.serialization.json.*

data class BridgeRequest(val id: String, val type: String, val payload: JsonObject)
data class NotificationContent(val title: String, val body: String, val targetUrl: String, val tag: String?)

object BridgeProtocol {
    const val MAX_MESSAGE = 16384
    fun requiresForeground(type: String): Boolean = type in setOf("requestNotificationPermission", "changeWebsite", "readCredential", "saveCredential")
    fun parse(raw: String): BridgeRequest? {
        if (raw.length > MAX_MESSAGE) return null
        val value = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        val version = value["version"] as? JsonPrimitive ?: return null
        if (version.isString || version.intOrNull != 1) return null
        val id = (value["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        val type = (value["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (type !in setOf("pageReady", "capabilities", "requestNotificationPermission", "showNotification", "changeWebsite", "readCredential", "saveCredential")) return null
        val payload = value["payload"] as? JsonObject ?: return null
        if (type == "pageReady" && payload.isNotEmpty()) return null
        return BridgeRequest(id, type, payload)
    }

    fun credentialPassword(payload: JsonObject): String? {
        if (payload.keys != setOf("password")) return null
        return (payload["password"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotEmpty() && it.length <= PlatformCredentialStore.MAX_PASSWORD }
    }

    fun notification(site: Site, payload: JsonObject): NotificationContent? {
        fun bounded(key: String, limit: Int): String? = (payload[key] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.length <= limit && it.none { char -> char == '\u0000' } }
        val title = bounded("title", 120)?.takeIf { it.isNotBlank() } ?: return null
        val body = bounded("body", 500) ?: return null
        if ("tag" in payload && bounded("tag", 80) == null) return null
        if ("targetUrl" in payload && bounded("targetUrl", 2048) == null) return null
        val target = NavigationPolicy.notificationTarget(site, bounded("targetUrl", 2048)) ?: return null
        return NotificationContent(title, body, target, bounded("tag", 80))
    }

    fun reply(id: String, result: JsonObject? = null, error: String? = null): String = buildJsonObject {
        put("version", 1); put("id", id); put("ok", error == null)
        if (error != null) put("error", error) else put("result", result ?: buildJsonObject {})
    }.toString()

    fun generationMatches(mainFrame: Boolean, sourceOrigin: String, site: Site, current: Long, captured: Long): Boolean =
        mainFrame && current == captured && NavigationPolicy.origin(sourceOrigin) == site.origin

    fun allowed(site: Site, sourceOrigin: String, mainFrame: Boolean, committedUrl: String?, current: Long, captured: Long, type: String = "capabilities"): Boolean {
        if (!generationMatches(mainFrame, sourceOrigin, site, current, captured)) return false
        if (type == "pageReady") return true
        return committedUrl != null && NavigationPolicy.decide(site, committedUrl) == Navigation.INTERNAL
    }
}

object FileSelectionPolicy {
    const val MAX_FILES = 8
    fun allowedUri(raw: String): Boolean = raw.startsWith("content://") && raw.length <= 4096 &&
        raw.none { it.isISOControl() } && runCatching { java.net.URI(raw).authority?.isNotBlank() == true }.getOrDefault(false)
    fun allowedProvider(providerUid: Int?, appUid: Int): Boolean = providerUid != null && providerUid != appUid
}
