package com.ilia.postupdateanalyzer.metadata.model

/** What the on-phone helper reports about one app. */
class AppMetadata(
    val packageName: String,
    val label: String?,
    val versionCode: Long?,
    val versionName: String?,
    val system: Boolean?,
    val updatedSystem: Boolean?,
    val firstInstallTime: Long?,
    val lastUpdateTime: Long?,
    val apkPath: String?,
    val iconPng: ByteArray?,
    val error: String?,
)
