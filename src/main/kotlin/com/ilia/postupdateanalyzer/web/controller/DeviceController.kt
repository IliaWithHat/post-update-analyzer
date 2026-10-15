package com.ilia.postupdateanalyzer.web.controller

import com.ilia.postupdateanalyzer.action.repository.ActionLogRepository
import com.ilia.postupdateanalyzer.device.model.Device
import com.ilia.postupdateanalyzer.device.service.DeviceService
import com.ilia.postupdateanalyzer.snapshot.model.Snapshot
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotKind
import com.ilia.postupdateanalyzer.snapshot.service.SnapshotService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class DeviceController(
    private val devices: DeviceService,
    private val snapshots: SnapshotService,
    private val actionLog: ActionLogRepository,
) {

    @GetMapping("/devices/{id}")
    fun device(@PathVariable id: Long, @RequestParam(required = false) user: Int?, model: Model): String {
        val device = devices.get(id)
        val live = devices.liveOrNull(device)
        val users = live?.let { runCatching { devices.users(it) }.getOrNull() }.orEmpty()
        val selectedUser = user ?: users.firstOrNull { it.current }?.id ?: 0
        model.addAttribute("device", device)
        model.addAttribute("live", live)
        model.addAttribute("users", users)
        fillSnapshots(model, device, selectedUser)
        return "device"
    }

    @PostMapping("/devices/{id}/snapshots")
    fun capture(
        @PathVariable id: Long,
        @RequestParam user: Int,
        @RequestParam kind: SnapshotKind,
        @RequestParam(required = false) note: String?,
        model: Model,
    ): String {
        val snapshot = snapshots.capture(id, user, kind, note)
        val device = devices.get(id)
        fillSnapshots(model, device, user)
        model.addAttribute("toastMessage", "Saved \"${snapshot.kind.title}\" snapshot with ${snapshot.packageCount} apps.")
        model.addAttribute("toastKind", if (snapshot.metadataMessage == null) "success" else "warning")
        model.addAttribute("toastDetail", snapshot.metadataMessage?.let { "${snapshot.metadataStatus.title}: $it" })
        return "responses :: snapshots-with-toast"
    }

    @DeleteMapping("/snapshots/{id}")
    fun delete(@PathVariable id: Long, model: Model): String {
        val snapshot = snapshots.get(id)
        snapshots.delete(id)
        fillSnapshots(model, devices.get(snapshot.deviceId), snapshot.androidUserId)
        model.addAttribute("toastMessage", "Snapshot deleted.")
        model.addAttribute("toastKind", "success")
        return "responses :: snapshots-with-toast"
    }

    private fun fillSnapshots(model: Model, device: Device, user: Int) {
        val all = snapshots.list(device.id)
        val forUser = all.filter { it.androidUserId == user }
        model.addAttribute("device", device)
        model.addAttribute("selectedUser", user)
        model.addAttribute("snapshots", all)
        model.addAttribute("userSnapshots", forUser)
        model.addAttribute("actionCounts", actionLog.countBySnapshot(device.id))
        val (before, after) = defaultPair(forUser)
        model.addAttribute("defaultBefore", before?.id)
        model.addAttribute("defaultAfter", after?.id)
        model.addAttribute("kinds", SnapshotKind.entries)
        model.addAttribute("suggestedKind", if (forUser.any { it.kind == SnapshotKind.BEFORE_UPDATE }) SnapshotKind.AFTER_UPDATE else SnapshotKind.BEFORE_UPDATE)
    }

    companion object {
        /** Latest "after" vs the latest "before" taken earlier; falls back to the two newest snapshots. */
        fun defaultPair(newestFirst: List<Snapshot>): Pair<Snapshot?, Snapshot?> {
            val after = newestFirst.firstOrNull { it.kind == SnapshotKind.AFTER_UPDATE }
            val before = after?.let { a -> newestFirst.firstOrNull { it.kind == SnapshotKind.BEFORE_UPDATE && it.createdAt < a.createdAt } }
            if (before != null) return before to after
            return newestFirst.getOrNull(1) to newestFirst.getOrNull(0)
        }
    }
}
