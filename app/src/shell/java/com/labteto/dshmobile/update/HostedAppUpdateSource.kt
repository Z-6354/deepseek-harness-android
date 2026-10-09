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
            response.body?.string()?.takeIf { it.length <= MAX_META_BYTES }
        } ?: return@withContext null
        parseManifest(text)
    }

    companion object {
        const val DEFAULT_MANIFEST_URL = "https://dsh.wannian.fun/dsha/update/latest.json"
        private const val MAX_META_BYTES = 64 * 1024

        fun parseManifest(text: String): AppUpdateOffer? {
            val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val versionName = AppVersion.normalizeName(
                root["versionName"]?.jsonPrimitive?.contentOrNull
                    ?: root["tag"]?.jsonPrimitive?.contentOrNull
                    ?: return null,
            )
            val versionCode = root["versionCode"]?.jsonPrimitive?.intOrNull
                ?: AppVersion.codeFromName(versionName)
            val apkUrl = root["apkUrl"]?.jsonPrimitive?.contentOrNull ?: return null
            if (!GitHubAppUpdateSource.isAllowedDownloadUrl(apkUrl)) return null
            if (!apkUrl.startsWith("https://", ignoreCase = true) &&
                !apkUrl.startsWith("http://127.0.0.1:", ignoreCase = true) &&
                !apkUrl.startsWith("http://localhost:", ignoreCase = true)
            ) return null
            val apkName = root["apkName"]?.jsonPrimitive?.contentOrNull
                ?: apkUrl.substringAfterLast('/').ifBlank { "update.apk" }
            val apkBytes = root["apkBytes"]?.jsonPrimitive?.longOrNull
                ?: root["size"]?.jsonPrimitive?.longOrNull
                ?: -1L
            if (apkBytes > GitHubAppUpdateSource.MAX_APK_BYTES) return null
            val sha256 = root["sha256"]?.jsonPrimitive?.contentOrNull
                ?.lowercase()
                ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            val notes = root["releaseNotes"]?.jsonPrimitive?.contentOrNull?.trim()?.take(2_000)
            return AppUpdateOffer(versionName, versionCode, apkUrl, apkName, apkBytes, sha256, notes)
        }
    }
}
