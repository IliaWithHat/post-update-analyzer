package com.ilia.postupdateanalyzer.action.model

import com.ilia.postupdateanalyzer.adb.model.PackageAction
import com.ilia.postupdateanalyzer.adb.model.PackageState
import com.ilia.postupdateanalyzer.device.model.LivePackage
import java.time.Instant

data class ActionRequest(
    val deviceId: Long,
    val userId: Int,
    val packageName: String,
    val action: PackageAction,
    val beforeSnapshotId: Long,
    val afterSnapshotId: Long,
)

data class ActionOutcome(val entry: ActionLogEntry, val current: LivePackage?)

/** One user action and what happened on the phone. A `null` state means the app was not on the phone. */
data class ActionLogEntry(
    val id: Long = 0,
    val createdAt: Instant,
    val deviceId: Long,
    val androidUserId: Int,
    val packageName: String,
    val label: String?,
    val action: PackageAction,
    val beforeSnapshotId: Long,
    val afterSnapshotId: Long,
    val stateBefore: PackageState?,
    val stateAfter: PackageState?,
    val status: ActionStatus,
    val commands: String?,
    val output: String?,
    val message: String?,
    val warning: String?,
    val durationMs: Long,
) {
    val displayName: String
        get() = label ?: packageName
}
