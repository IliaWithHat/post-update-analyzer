package com.ilia.postupdateanalyzer.web.controller

import com.ilia.postupdateanalyzer.action.model.ActionRequest
import com.ilia.postupdateanalyzer.action.model.ActionStatus
import com.ilia.postupdateanalyzer.action.service.PackageActionService
import com.ilia.postupdateanalyzer.adb.model.PackageAction
import com.ilia.postupdateanalyzer.compare.service.ComparisonService
import com.ilia.postupdateanalyzer.device.exception.NotFoundException
import com.ilia.postupdateanalyzer.device.service.DeviceService
import com.ilia.postupdateanalyzer.web.model.AppRow
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/** One click = one action on one app. Returns the updated table row and a result message. */
@Controller
class ActionController(
    private val actions: PackageActionService,
    private val comparisons: ComparisonService,
    private val devices: DeviceService,
) {

    @PostMapping("/devices/{deviceId}/actions")
    fun apply(
        @PathVariable deviceId: Long,
        @RequestParam("package") packageName: String,
        @RequestParam action: PackageAction,
        @RequestParam before: Long,
        @RequestParam after: Long,
        model: Model,
    ): String {
        val comparison = comparisons.compare(before, after)
        if (comparison.device.id != deviceId) throw IllegalArgumentException("The snapshots do not belong to this phone.")
        val diff = comparison.diff(packageName) ?: throw NotFoundException("$packageName is not part of this comparison.")

        val request = ActionRequest(deviceId, comparison.after.androidUserId, packageName, action, comparison.before.id, comparison.after.id)
        val outcome = actions.apply(request)

        val entry = outcome.entry
        val live = devices.liveOrNull(comparison.device)
        model.addAttribute("row", if (live != null) AppRow(diff, true, outcome.current) else AppRow(diff, false, null))
        model.addAttribute("comparison", comparison)
        model.addAttribute("toastMessage", "${action.title} ${diff.label}: ${entry.message ?: entry.status.title}")
        model.addAttribute("toastDetail", entry.warning)
        model.addAttribute(
            "toastKind",
            when (entry.status) {
                ActionStatus.SUCCESS -> if (entry.warning != null) "warning" else "success"
                ActionStatus.SKIPPED -> "info"
                ActionStatus.FAILED -> "error"
            },
        )
        return "comparison-row :: row-with-toast"
    }
}
