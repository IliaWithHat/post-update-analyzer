package com.ilia.postupdateanalyzer.support.fake

import com.ilia.postupdateanalyzer.adb.environment.AdbEnvironment
import com.ilia.postupdateanalyzer.adb.model.AdbLocation
import com.ilia.postupdateanalyzer.adb.model.AdbStatus
import kotlin.io.path.Path

class FakeAdbEnvironment : AdbEnvironment {
    var available = true
    var configuredPath: String? = null

    override fun status(refresh: Boolean): AdbStatus {
        return if (available) {
            AdbStatus(AdbLocation(Path(configuredPath ?: "/fake/platform-tools/adb"), "PATH", "Android Debug Bridge version 1.0.41"), configuredPath, emptyList(), emptyList())
        } else {
            AdbStatus(null, configuredPath, listOfNotNull(configuredPath?.let { "The ADB path from Settings does not work: $it" }), listOf(Path("/usr/bin/adb")))
        }
    }

    override fun configure(path: String?): AdbStatus {
        configuredPath = path?.takeIf { it.isNotBlank() }
        available = configuredPath == null || configuredPath == GOOD_PATH
        return status()
    }

    companion object {
        const val GOOD_PATH = "/opt/platform-tools/adb"
    }
}
