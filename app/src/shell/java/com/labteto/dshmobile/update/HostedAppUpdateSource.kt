package com.labteto.dshmobile.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Primary update channel for China: HTTPS JSON on the operator's own host.
 * Default: `https://dsh.wannian.fun/dsha/update/latest.json`
 */
class HostedAppUpdateSource(
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    private val client: OkHttpClient = GitHubAppUpdateSource.defaultClient(),
) {
    suspend fun latest(): AppUpdateOffer? = withContext(Dispatchers.IO) {
        if (!GitHubAppUpdateSource.isAllowedDownloadUrl(manifestUrl)) return@withContext null
        val request = Request.Builder()
            .url(manifestUrl)
            .header("User-Agent", "DSHA-AppUpdate")
            .header("Accept", "application/json")
            .get()
            .build()
        val text = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            // Bounded read: string() would buffer an arbitrarily large body before the length check.
            response.body?.source()?.let { source ->
                source.request(MAX_META_BYTES + 1L)
                if (source.buffer.size > MAX_META_BYTES) null else source.buffer.readUtf8()
            }
        } ?: return@withContext null
        parseManifest(text)
    }

    companion object {
        const val DEFAULT_MANIFEST_URL = "https://dsh.wannian.fun/dsha/update/latest.json"
        private const val MAX_META_BYTES = 64 * 1024

        fun parseManifest(text: String): AppUpdateOffer? {
            val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            // `as? JsonPrimitive`, never `.jsonPrimitive`: a field that is an object/array must mean "no offer", not a throw.
            fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
            val versionName = AppVersion.normalizeName(
                root.primitive("versionName")?.contentOrNull
                    ?: root.primitive("tag")?.contentOrNull
                    ?: return null,
            )
            val versionCode = root.primitive("versionCode")?.intOrNull
                ?: AppVersion.codeFromName(versionName)
            val apkUrl = root.primitive("apkUrl")?.contentOrNull ?: return null
            if (!GitHubAppUpdateSource.isAllowedDownloadUrl(apkUrl)) return null
            if (!apkUrl.startsWith("https://", ignoreCase = true) &&
                !apkUrl.startsWith("http://127.0.0.1:", ignoreCase = true) &&
                !apkUrl.startsWith("http://localhost:", ignoreCase = true)
            ) return null
            val apkName = root.primitive("apkName")?.contentOrNull
                ?: apkUrl.substringAfterLast('/').ifBlank { "update.apk" }
            val apkBytes = root.primitive("apkBytes")?.longOrNull
                ?: root.primitive("size")?.longOrNull
                ?: -1L
            if (apkBytes > GitHubAppUpdateSource.MAX_APK_BYTES) return null
            val sha256 = root.primitive("sha256")?.contentOrNull
                ?.lowercase()
                ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            val notes = root.primitive("releaseNotes")?.contentOrNull?.trim()?.take(2_000)
            return AppUpdateOffer(versionName, versionCode, apkUrl, apkName, apkBytes, sha256, notes)
        }
    }
}
