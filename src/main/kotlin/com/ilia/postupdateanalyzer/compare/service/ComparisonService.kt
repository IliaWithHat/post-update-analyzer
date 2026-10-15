package com.ilia.postupdateanalyzer.compare.service

import com.ilia.postupdateanalyzer.compare.exception.InvalidComparisonException
import com.ilia.postupdateanalyzer.compare.model.ChangeType
import com.ilia.postupdateanalyzer.compare.model.Comparison
import com.ilia.postupdateanalyzer.compare.model.PackageDiff
import com.ilia.postupdateanalyzer.device.service.DeviceService
import com.ilia.postupdateanalyzer.snapshot.repository.SnapshotRepository
import com.ilia.postupdateanalyzer.snapshot.service.SnapshotService
import org.springframework.stereotype.Service

@Service
class ComparisonService(
    private val snapshotService: SnapshotService,
    private val snapshots: SnapshotRepository,
    private val devices: DeviceService,
) {

    /** Compares two snapshots of the same phone and user. The older one is always "before". */
    fun compare(firstId: Long, secondId: Long): Comparison {
        if (firstId == secondId) throw InvalidComparisonException("Pick two different snapshots to compare.")
        val first = snapshotService.get(firstId)
        val second = snapshotService.get(secondId)
        if (first.deviceId != second.deviceId) {
            throw InvalidComparisonException("These snapshots belong to different phones.")
        }
        if (first.androidUserId != second.androidUserId) {
            throw InvalidComparisonException(
                "These snapshots are of different Android users (${first.androidUserId} and ${second.androidUserId}).",
            )
        }
        val swapped = first.createdAt > second.createdAt || first.createdAt == second.createdAt && first.id > second.id
        val (before, after) = if (swapped) second to first else first to second

        val beforePackages = snapshots.packages(before.id).associateBy { it.packageName }
        val afterPackages = snapshots.packages(after.id).associateBy { it.packageName }
        val diffs = (beforePackages.keys + afterPackages.keys).map { name ->
            val old = beforePackages[name]
            val new = afterPackages[name]
            PackageDiff(name, old, new, ChangeType.of(old?.state, new?.state))
        }.sortedWith(compareBy<PackageDiff> { it.change.ordinal }.thenBy { it.label.lowercase() }.thenBy { it.packageName })

        return Comparison(devices.get(before.deviceId), before, after, diffs, swapped)
    }
}
