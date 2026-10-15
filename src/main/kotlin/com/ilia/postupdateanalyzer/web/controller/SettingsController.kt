package com.ilia.postupdateanalyzer.web.controller

import com.ilia.postupdateanalyzer.adb.environment.AdbEnvironment
import com.ilia.postupdateanalyzer.adb.model.AdbStatus
import com.ilia.postupdateanalyzer.config.AppProperties
import com.ilia.postupdateanalyzer.desktop.lifecycle.AppShutdown
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class SettingsController(
    private val adbEnvironment: AdbEnvironment,
    private val properties: AppProperties,
    private val buildProperties: ObjectProvider<BuildProperties>,
    private val shutdown: AppShutdown,
) {

    @GetMapping("/settings")
    fun settings(model: Model): String {
        fill(model, adbEnvironment.status(refresh = true))
        return "settings"
    }

    @PostMapping("/settings/adb")
    fun saveAdbPath(@RequestParam(required = false) path: String?, model: Model): String {
        val status = adbEnvironment.configure(path)
        fill(model, status)
        model.addAttribute("toastKind", if (status.available) "success" else "error")
        model.addAttribute(
            "toastMessage",
            when {
                status.available -> "ADB found: ${status.location!!.path}"
                path.isNullOrBlank() -> "ADB was not found automatically. Enter its path."
                else -> "ADB does not work at: $path"
            },
        )
        return "responses :: adb-with-toast"
    }

    @PostMapping("/quit")
    fun quit(): String {
        shutdown.quit()
        return "fragments :: goodbye"
    }

    private fun fill(model: Model, status: AdbStatus) {
        model.addAttribute("adb", status)
        model.addAttribute("dataDir", properties.dataDir.toAbsolutePath())
        model.addAttribute("version", buildProperties.ifAvailable?.version ?: "dev")
    }
}
