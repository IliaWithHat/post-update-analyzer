package com.ilia.postupdateanalyzer.web.controller

import com.ilia.postupdateanalyzer.action.model.ActionStatus
import com.ilia.postupdateanalyzer.action.repository.ActionLogRepository
import com.ilia.postupdateanalyzer.device.repository.DeviceRepository
import com.ilia.postupdateanalyzer.web.view.HistoryViews
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/** Read-only log of every action the user ran, grouped by the comparison it was run from. */
@Controller
class HistoryController(
    private val actionLog: ActionLogRepository,
    private val devices: DeviceRepository,
    private val views: HistoryViews,
) {

    @GetMapping("/history")
    fun history(
        @RequestParam(required = false) device: Long?,
        @RequestParam(required = false) status: ActionStatus?,
        model: Model,
    ): String {
        model.addAttribute("groups", views.groups(actionLog.find(device, status)))
        model.addAttribute("devices", devices.findAll())
        model.addAttribute("selectedDevice", device)
        model.addAttribute("selectedStatus", status)
        model.addAttribute("statuses", ActionStatus.entries)
        return "history"
    }
}
