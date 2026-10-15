package com.ilia.postupdateanalyzer.compare.model

import com.ilia.postupdateanalyzer.device.model.Device
import com.ilia.postupdateanalyzer.snapshot.model.Snapshot
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotPackage

data class PackageDiff(
    val packageName: String,
    val before: SnapshotPackage?,
    val after: SnapshotPackage?,
    val change: ChangeType,
) {
    private val latest: SnapshotPackage get() = after ?: before!!

    val label: String
        get() = after?.label ?: before?.label ?: packageName

    val iconHash: String?
        get() = after?.iconHash ?: before?.iconHash

    val system: Boolean
        get() = latest.system

    val versionChange: VersionChange
        get() {
            val old = before?.versionCode
            val new = after?.versionCode
            return when {
                old == null || new == null || old == new -> VersionChange.NONE
                new > old -> VersionChange.UPGRADED
                else -> VersionChange.DOWNGRADED
            }
        }
}

data class Comparison(
    val device: Device,
    val before: Snapshot,
    val after: Snapshot,
    val diffs: List<PackageDiff>,
    /** The snapshots were given in the wrong order and were swapped. */
    val swapped: Boolean,
) {
    val counts: Map<ChangeType, Int>
        get() = ChangeType.entries.associateWith { type -> diffs.count { it.change == type } }

    val changedCount: Int
        get() = diffs.count { it.change.changed }

    val buildChanged: Boolean
        get() = before.build.fingerprint != after.build.fingerprint

    fun diff(packageName: String): PackageDiff? {
        return diffs.firstOrNull { it.packageName == packageName }
    }
}
