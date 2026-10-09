package com.labteto.dshmobile.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AppUpdateInstaller(
    private val context: Context,
    private val client: OkHttpClient = downloadClient(),
) {
    sealed interface PrepareResult {
        data class Ready(val apk: File, val uri: Uri) : PrepareResult
        data class NeedPermission(val settingsIntent: Intent) : PrepareResult
        data class Failed(val message: String) : PrepareResult
    }

    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls() else true

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    suspend fun prepare(
        offer: AppUpdateOffer,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null,
    ): PrepareResult = withContext(Dispatchers.IO) {
        if (!canInstall()) return@withContext PrepareResult.NeedPermission(installPermissionIntent())
        if (!offer.apkUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext PrepareResult.Failed("更新地址必须是 HTTPS。")
        }
        if (offer.apkBytes > GitHubAppUpdateSource.MAX_APK_BYTES) {
            return@withContext PrepareResult.Failed("安装包过大。")
        }
        if (offer.versionCode <= installedVersionCode()) {
            return@withContext PrepareResult.Failed("服务器提供的安装包不高于当前版本。")
        }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val target = File(dir, safeApkFileName(offer.apkName))
        try {
            download(offer.apkUrl, target, offer.apkBytes, onProgress)
            offer.sha256?.let { expected ->
                val actual = sha256(target)
                if (!actual.equals(expected, ignoreCase = true)) {
                    target.delete()
                    return@withContext PrepareResult.Failed("安装包校验失败（SHA-256 不匹配）。")
                }
            }
            verifyPackage(target, offer.versionCode)?.let { reason ->
                target.delete()
                return@withContext PrepareResult.Failed(reason)
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.update", target)
            PrepareResult.Ready(target, uri)
        } catch (error: Exception) {
            target.delete()
            PrepareResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: "下载更新失败。")
        }
    }

    /** Start from an Activity; do not force a new task (OEM installers often strand on the home screen). */
    fun installIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun download(
        url: String,
        target: File,
        expectedBytes: Long,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)?,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "DSHA-AppUpdate")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "下载失败（HTTP ${response.code}）。" }
            val body = response.body ?: error("空响应。")
            val length = body.contentLength()
            if (length > GitHubAppUpdateSource.MAX_APK_BYTES) error("安装包过大。")
            val totalHint = when {
                expectedBytes > 0 -> expectedBytes
                length > 0 -> length
                else -> -1L
            }
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var lastReport = -1L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= GitHubAppUpdateSource.MAX_APK_BYTES) { "安装包过大。" }
                        output.write(buffer, 0, read)
                        if (onProgress != null && (total - lastReport >= 256 * 1024 || lastReport < 0)) {
                            lastReport = total
                            onProgress(total, totalHint)
                        }
                    }
                    output.flush()
                    check(total > 1_000) { "安装包不完整。" }
                    if (expectedBytes > 0) check(total == expectedBytes) { "安装包大小不匹配。" }
                    onProgress?.invoke(total, if (totalHint > 0) totalHint else total)
                }
            }
        }
    }

    private fun verifyPackage(apk: File, expectedVersionCode: Int): String? {
        val archive = packageInfo(apk) ?: return "无法解析安装包。"
        archive.applicationInfo?.apply {
            sourceDir = apk.absolutePath
            publicSourceDir = apk.absolutePath
        }
        if (archive.packageName != context.packageName) {
            return "安装包应用 ID 与当前应用不一致（调试包与正式包不能互相覆盖）。"
        }
        val archiveCode = versionCodeOf(archive)
        if (archiveCode <= installedVersionCode()) {
            return "安装包版本不高于当前版本，已取消安装（避免模拟器卡在「安装中」）。"
        }
        if (expectedVersionCode > 0 && archiveCode != expectedVersionCode.toLong()) {
            return "安装包版本与更新频道声明不一致。"
        }
        val archiveCodes = signingDigests(archive) ?: return "安装包无有效签名。"
        val installed = try {
            if (Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
        } catch (_: Exception) {
            return "无法读取当前应用签名。"
        }
        val installedCodes = signingDigests(installed) ?: return "当前应用无有效签名。"
        if (archiveCodes.none { it in installedCodes }) {
            return "安装包签名与当前应用不一致，已拒绝安装。"
        }
        return null
    }

    private fun installedVersionCode(): Long = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        versionCodeOf(info)
    } catch (_: Exception) {
        0L
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun packageInfo(apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        }

    private fun signingDigests(info: PackageInfo): Set<String>? {
        if (Build.VERSION.SDK_INT >= 28) {
            val signers = info.signingInfo ?: return null
            val signatures = if (signers.hasMultipleSigners()) signers.apkContentsSigners else signers.signingCertificateHistory
            return signatures?.mapNotNull { sha256(it.toByteArray()) }?.toSet()?.takeIf { it.isNotEmpty() }
        }
        @Suppress("DEPRECATION")
        return info.signatures?.mapNotNull { sha256(it.toByteArray()) }?.toSet()?.takeIf { it.isNotEmpty() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * The manifest is server-controlled, so the name may not escape `updates/`: `..` and `.` survive the
         * character filter and would resolve to the parent directory. Always yield a plain `*.apk` leaf.
         */
        internal fun safeApkFileName(raw: String): String {
            val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^\\w.\\-]"), "_").trim('.')
            val stem = cleaned.ifBlank { "update" }
            return if (stem.endsWith(".apk", ignoreCase = true)) stem else "$stem.apk"
        }

        // followSslRedirects(false): never follow an https -> http downgrade; https -> https still follows.
        fun downloadClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}
