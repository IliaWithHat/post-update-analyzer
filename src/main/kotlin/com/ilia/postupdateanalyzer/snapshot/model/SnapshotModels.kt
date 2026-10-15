package com.ilia.postupdateanalyzer.snapshot.model

import com.ilia.postupdateanalyzer.adb.model.PackageState
import com.ilia.postupdateanalyzer.metadata.model.MetadataStatus
import java.time.Instant

data class BuildInfo(
    val fingerprint: String?,
    val displayId: String?,
    val incremental: String?,
    val androidRelease: String?,
    val sdkInt: Int?,
    val securityPatch: String?,
)

data class Snapshot(
    val id: Long,
    val deviceId: Long,
    val androidUserId: Int,
    val kind: SnapshotKind,
    val note: String?,
    val createdAt: Instant,
    val adbSerial: String?,
    val build: BuildInfo,
    val packageCount: Int,
    val metadataStatus: MetadataStatus,
    val metadataMessage: String?,
)

data class SnapshotPackage(
    val packageName: String,
    val state: PackageState,
    val system: Boolean,
    val updatedSystem: Boolean?,
    val versionCode: Long?,
    val versionName: String?,
    val label: String?,
    val apkPath: String?,
    val firstInstallTime: Long?,
    val lastUpdateTime: Long?,
    val iconHash: String?,
)

data class NewSnapshot(
    val deviceId: Long,
    val androidUserId: Int,
    val kind: SnapshotKind,
    val note: String?,
    val createdAt: Instant,
    val adbSerial: String,
    val build: BuildInfo,
    val metadataStatus: MetadataStatus,
    val metadataMessage: String?,
    val packages: List<SnapshotPackage>,
    /** hash -> PNG */
    val icons: Map<String, ByteArray>,
)
