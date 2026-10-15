package com.ilia.postupdateanalyzer.web.model

import com.ilia.postupdateanalyzer.action.model.ActionLogEntry
import com.ilia.postupdateanalyzer.device.model.Device
import com.ilia.postupdateanalyzer.snapshot.model.Snapshot

/** The actions run from one comparison (a before/after snapshot pair), newest first. */
data class HistoryGroup(
    val device: Device,
    val before: Snapshot,
    val after: Snapshot,
    val entries: List<ActionLogEntry>,
) {
    val id: String
        get() = "compare-${before.id}-${after.id}"
}
