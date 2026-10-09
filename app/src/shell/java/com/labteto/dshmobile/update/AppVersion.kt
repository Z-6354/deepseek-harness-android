package com.labteto.dshmobile.update

/**
 * Same encoding as [app/build.gradle.kts]: major*10000 + minor*100 + patch.
 * Pre-release suffix (`-alpha.1`) is ignored for ordering.
 */
object AppVersion {
    fun codeFromName(versionName: String): Int {
        val core = versionName.trim().removePrefix("v").substringBefore('-')
        val parts = core.split('.').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
    }

    fun normalizeName(raw: String): String = raw.trim().removePrefix("v").ifBlank { "0.0.0" }
}
