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
        // Retire legacy native chat preferences; webpage plugins own startup intent.
        if (preferences.contains("lastSessionId")) check(preferences.edit().remove("lastSessionId").commit())
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
    fun privateFileStore(ownerOverride: String? = owner): PrivateFileStore = PrivateFileStore(
        File(context.noBackupFilesDir, "private-cache-v1"), ownerOverride ?: active()?.owner ?: "unclaimed-owner",
    )

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
        // The durable storage fence precedes WebView/task teardown and survives process death.
        privateFileStore().beginCleanup(transaction.nonce)
        transaction
    }
    fun pendingCleanup(): CleanupTransaction? = synchronized(cleanupLock) {
        if (!cleanupPending) return@synchronized null
        val nonce = preferences.getString("cleanupNonce", null) ?: java.util.UUID.randomUUID().toString().also {
            check(preferences.edit().putString("cleanupNonce", it).putString("cleanupEpoch", storageEpoch).commit())
        }
        CleanupTransaction(nonce, targetOwner, preferences.getString("cleanupEpoch", storageEpoch)!!)
    }
    fun preparePrivateFileCleanup(transaction: CleanupTransaction): Boolean = synchronized(cleanupLock) {
        if (pendingCleanup() != transaction) return@synchronized false
        PrivateCleanupFence(privateFileStore(owner ?: transaction.targetOwner ?: "unclaimed-owner")).prepare(transaction)
    }
    fun blockForLocalLogout(invalidate: () -> Unit) {
        val transaction = beginCleanup(null)
        invalidate()
        clearStaticAssets()
        // WebView browsing-data delete is unavailable; still wipe durable private blobs while
        // leaving the fence blocked so a later full clear is required before login.
        privateFileStore().deleteForCleanup(transaction.nonce)
    }
    fun completeCleanup(transaction: CleanupTransaction): Boolean = synchronized(cleanupLock) {
        if (pendingCleanup() != transaction || storageEpoch != transaction.storageEpoch || active()?.owner != transaction.targetOwner) return@synchronized false
        val fence = PrivateCleanupFence(privateFileStore(transaction.targetOwner ?: owner ?: "unclaimed-owner"))
        // Finish the disk fence before advancing owner / clearing cleanupPending so a crash
        // cannot leave prefs showing the new owner while the fence is still blocked.
        if (!fence.finish(transaction)) return@synchronized false
        if (!preferences.edit().putString("owner", transaction.targetOwner).putString("ownerEpoch", storageEpoch)
                .putBoolean("fresh", false).remove("targetOwner").remove("cleanupNonce").remove("cleanupEpoch")
                .putBoolean("cleanupPending", false).commit()) return@synchronized false
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
