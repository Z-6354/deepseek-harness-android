package com.labteto.dshmobile.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Reads the latest GitHub Release APK (+ optional SHA256SUMS). */
class GitHubAppUpdateSource(
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO,
    private val client: OkHttpClient = defaultClient(),
    private val apiBase: String = "https://api.github.com",
) {
    suspend fun latest(): AppUpdateOffer? = withContext(Dispatchers.IO) {
        val release = getJson("$apiBase/repos/$owner/$repo/releases/latest") ?: return@withContext null
        if (release["draft"]?.jsonPrimitive?.booleanOrNull == true) return@withContext null
        if (release["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return@withContext null
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
        val versionName = AppVersion.normalizeName(tag)
        val versionCode = AppVersion.codeFromName(versionName)
        val assets = release["assets"] as? JsonArray ?: return@withContext null
        val apk = assets.mapNotNull { it as? JsonObject }.firstOrNull { asset ->
            val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            name.endsWith(".apk", ignoreCase = true) && !name.contains("debug", ignoreCase = true)
        } ?: return@withContext null
        val apkName = apk["name"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
        val apkUrl = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
        val apkBytes = apk["size"]?.jsonPrimitive?.longOrNull ?: -1L
        if (!isAllowedDownloadUrl(apkUrl)) return@withContext null
        if (apkBytes > MAX_APK_BYTES) return@withContext null
        val sumsUrl = assets.mapNotNull { it as? JsonObject }.firstOrNull {
            it["name"]?.jsonPrimitive?.contentOrNull.equals("SHA256SUMS.txt", ignoreCase = true)
        }?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
        val sha256 = sumsUrl?.takeIf { isAllowedDownloadUrl(it) }?.let { fetchSha256(it, apkName) }
        val notes = release["body"]?.jsonPrimitive?.contentOrNull?.trim()?.take(2_000)
        AppUpdateOffer(versionName, versionCode, apkUrl, apkName, apkBytes, sha256, notes)
    }

    private fun fetchSha256(sumsUrl: String, apkName: String): String? {
        val body = getText(sumsUrl) ?: return null
        val line = body.lineSequence().firstOrNull { it.trimEnd().endsWith(apkName) } ?: return null
        val hash = line.trim().substringBefore(' ').trim().lowercase()
        return hash.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
    }

    private fun getJson(url: String): JsonObject? {
        val text = getText(url) ?: return null
        return runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }

    private fun getText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DSHA-AppUpdate")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            if (body.length > MAX_META_BYTES) return null
            return body
        }
    }

    companion object {
        const val DEFAULT_OWNER = "Z-6354"
        const val DEFAULT_REPO = "deepseek-harness-android"
        const val MAX_APK_BYTES = 120L * 1024L * 1024L
        private const val MAX_META_BYTES = 512 * 1024

        fun isAllowedDownloadUrl(url: String): Boolean {
            if (url.startsWith("https://", ignoreCase = true)) return true
            // Loopback HTTP only for unit tests against MockWebServer.
            return url.startsWith("http://127.0.0.1:", ignoreCase = true) ||
                url.startsWith("http://localhost:", ignoreCase = true)
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
