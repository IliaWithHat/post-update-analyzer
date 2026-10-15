package com.ilia.postupdateanalyzer.web.view

import com.ilia.postupdateanalyzer.action.model.ActionLogEntry
import com.ilia.postupdateanalyzer.device.repository.DeviceRepository
import com.ilia.postupdateanalyzer.snapshot.repository.SnapshotRepository
import com.ilia.postupdateanalyzer.web.model.HistoryGroup
import org.springframework.stereotype.Component

@Component
class HistoryViews(
    private val devices: DeviceRepository,
    private val snapshots: SnapshotRepository,
) {

    /** Groups log entries (newest first) by their comparison; the comparison with the newest action comes first. */
    fun groups(entries: List<ActionLogEntry>): List<HistoryGroup> {
        val deviceById = devices.findAll().associateBy { it.id }
        val snapshotById = entries.map { it.deviceId }.distinct().flatMap { snapshots.findByDevice(it) }.associateBy { it.id }
        return entries.groupBy { it.beforeSnapshotId to it.afterSnapshotId }.map { (pair, group) ->
            HistoryGroup(
                device = deviceById.getValue(group.first().deviceId),
                before = snapshotById.getValue(pair.first),
                after = snapshotById.getValue(pair.second),
                entries = group,
            )
        }
    }
}
