package com.ilia.postupdateanalyzer.web.controller

import com.ilia.postupdateanalyzer.device.service.DeviceService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class HomeController(private val devices: DeviceService) {

    @GetMapping("/")
    fun home(model: Model): String {
        model.addAttribute("scan", devices.scan())
        return "home"
    }

    /** Polled by the home page so newly plugged-in phones appear without reloading. */
    @GetMapping("/fragments/devices")
    fun devicesFragment(model: Model): String {
        model.addAttribute("scan", devices.scan())
        return "home :: devices"
    }
}
