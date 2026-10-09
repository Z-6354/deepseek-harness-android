package com.labteto.dshmobile.browser

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.File
import java.security.KeyStore

private val Context.legacyPreferences by preferencesDataStore(name = "dsh_mobile")

class SiteRepository(private val context: Context, preferencesName: String = "browser_sites_v1") {
    private val ownsInstallationEpoch = preferencesName == "browser_sites_v1"
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    val freshBeforeMigration = !File(context.filesDir, "datastore/dsh_mobile.preferences_pb").exists() &&
        !File(context.applicationInfo.dataDir, "app_webview").exists()

    suspend fun migrate() {
        if (preferences.getBoolean("migrated", false)) return
        // Copy only display/address metadata. Never decrypt or promote a credential.
        if (!preferences.getBoolean("addressesImported", false)) {
            val legacy = context.legacyPreferences.data.first()
            val sites = LegacySiteMigration.extract(legacy[stringPreferencesKey("hosts_json")])
            if (!preferences.edit().putString("sites", json.encodeToString(sites))
                    .putBoolean("fresh", freshBeforeMigration || BrowserStorage.isFreshEpoch).putBoolean("addressesImported", true).commit()) error("Address migration failed")
        }
        // Retire the complete old settings store: it is dedicated to the former client.
        context.legacyPreferences.edit { it.clear() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        listOf("dsh_password_sessions_v1", "dsh_relay_tokens").forEach {
            if (store.containsAlias(it)) store.deleteEntry(it)
        }
        if (!preferences.edit().putBoolean("migrated", true).commit()) error("Migration failed")
    }

    fun clearStaticAssets() { StaticAssetCache.clear(context.cacheDir) }

    fun sites(): List<Site> = runCatching {
        json.decodeFromString<List<Site>>(preferences.getString("sites", "[]")!!)
    }.getOrDefault(emptyList()).filter { NavigationPolicy.normalizedEntry(it.entryUrl) == it.entryUrl }

    fun save(site: Site) {
        check(preferences.edit().putString("sites", json.encodeToString(listOf(site) + sites().filterNot { it.id == site.id })).commit())
    }
    fun active(): Site? = sites().firstOrNull { it.id == preferences.getString("active", null) }
    val migrated: Boolean get() = preferences.getBoolean("migrated", false)
    fun lastDocument(site: Site): String? = preferences.getString("lastDocument", null)
        ?.takeIf { NavigationPolicy.decide(site, it) == Navigation.INTERNAL }
    fun rememberDocument(site: Site, url: String) {
        if (NavigationPolicy.decide(site, url) == Navigation.INTERNAL) preferences.edit().putString("lastDocument", url).apply()
    }
    fun clearLastDocument() { preferences.edit().remove("lastDocument").commit() }

    /** Last non-blank session id remembered for cold-start reopen (SPA has no session URL). */
    fun lastSessionId(site: Site): String? = preferences.getString("lastSessionId", null)
        ?.takeIf { it.isNotBlank() && owner == site.owner }
    fun rememberSessionId(site: Site, sessionId: String) {
        if (sessionId.isBlank() || owner != site.owner) return
        preferences.edit().putString("lastSessionId", sessionId).apply()
    }
    fun clearLastSessionId() { preferences.edit().remove("lastSessionId").commit() }
    fun defaultSite(): Site = sites().firstOrNull { it.entryUrl == "https://dsh.wannian.fun/" }
        ?: Site(name = "dsh.wannian.fun", entryUrl = "https://dsh.wannian.fun/", staticResourcePrefixes = listOf("/assets/", "/plugins/")).also(::save)
    fun select(site: Site) { check(preferences.edit().putString("active", site.id).commit()) }
    private val storageEpoch: String get() = BrowserStorage.suffix ?: "legacy-default"
    val owner: String? get() = preferences.getString("owner", null)?.takeIf {
        preferences.getString("ownerEpoch", "legacy-default") == storageEpoch
    }
    val cleanupPending: Boolean get() = preferences.getBoolean("cleanupPending", false)
    val fresh: Boolean get() = if (BrowserStorage.suffix != null) BrowserStorage.isFreshEpoch else preferences.getBoolean("fresh", false)
    val targetOwner: String? get() = preferences.getString("targetOwner", null)

    fun beginCleanup(target: Site?): CleanupTransaction = synchronized(cleanupLock) {
        val transaction = CleanupTransaction(java.util.UUID.randomUUID().toString(), target?.owner, storageEpoch)
        check(preferences.edit().putBoolean("cleanupPending", true).putString("targetOwner", transaction.targetOwner)
            .putString("cleanupNonce", transaction.nonce).putString("cleanupEpoch", transaction.storageEpoch)
            .putString("active", target?.id).putBoolean("fresh", false).remove("lastDocument").remove("lastSessionId").commit())
        transaction
    }
    fun pendingCleanup(): CleanupTransaction? = synchronized(cleanupLock) {
        if (!cleanupPending) return@synchronized null
        val nonce = preferences.getString("cleanupNonce", null) ?: java.util.UUID.randomUUID().toString().also {
            check(preferences.edit().putString("cleanupNonce", it).putString("cleanupEpoch", storageEpoch).commit())
        }
        CleanupTransaction(nonce, targetOwner, preferences.getString("cleanupEpoch", storageEpoch)!!)
    }
    fun blockForLocalLogout(invalidate: () -> Unit) {
        beginCleanup(null)
        invalidate()
        clearStaticAssets()
    }
    fun completeCleanup(transaction: CleanupTransaction): Boolean = synchronized(cleanupLock) {
        if (pendingCleanup() != transaction || storageEpoch != transaction.storageEpoch || active()?.owner != transaction.targetOwner) return@synchronized false
        check(preferences.edit().putString("owner", transaction.targetOwner).remove("targetOwner")
            .remove("cleanupNonce").remove("cleanupEpoch")
            .putString("ownerEpoch", storageEpoch).putBoolean("cleanupPending", false).putBoolean("fresh", false).commit())
        if (ownsInstallationEpoch) BrowserStorage.claimEpoch()
        clearStaticAssets()
        true
    }
    fun claimFresh(site: Site) {
        check(owner == null && fresh && !cleanupPending)
        check(preferences.edit().putString("owner", site.owner).putString("ownerEpoch", storageEpoch).putBoolean("fresh", false).commit())
        if (ownsInstallationEpoch) BrowserStorage.claimEpoch()
    }
    val notificationToken: String get() {
        preferences.getString("notificationToken", null)?.let { return it }
        val token = java.util.UUID.randomUUID().toString()
        check(preferences.edit().putString("notificationToken", token).commit())
        return token
    }
    private companion object { val cleanupLock = Any() }
}

object LegacySiteMigration {
    fun extract(raw: String?): List<Site> {
        val array = raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: return emptyList()
        return array.mapNotNull { element ->
            val value = element as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (value[key] as? JsonPrimitive)?.contentOrNull
            if ((value["useTls"] as? JsonPrimitive)?.booleanOrNull != true) return@mapNotNull null
            if (listOf("relayDeviceId", "relayFingerprint", "relayPin").any { text(it) != null }) return@mapNotNull null
            val host = text("host") ?: return@mapNotNull null
            val port = text("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@mapNotNull null
            if (host.any { it in "/\\?#@" || it.isISOControl() }) return@mapNotNull null
            val authority = if (':' in host && !host.startsWith('[')) "[$host]" else host
            val entry = NavigationPolicy.normalizedEntry("https://$authority:$port/") ?: return@mapNotNull null
            Site(id = text("id")?.takeIf { it.length <= 128 } ?: java.util.UUID.randomUUID().toString(),
                name = text("name")?.take(120) ?: host, entryUrl = entry)
        }.distinctBy { it.id }
    }
}
