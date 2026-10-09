package com.labteto.dshmobile.update

data class AppUpdateOffer(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val apkName: String,
    val apkBytes: Long,
    val sha256: String?,
    val releaseNotes: String?,
)
