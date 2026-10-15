package com.ilia.postupdateanalyzer.adb.parser

import com.ilia.postupdateanalyzer.adb.command.PackageNames
import com.ilia.postupdateanalyzer.adb.model.AdbDevice
import com.ilia.postupdateanalyzer.adb.model.AndroidUser
import com.ilia.postupdateanalyzer.adb.model.DeviceConnectionState
import com.ilia.postupdateanalyzer.adb.model.PackageListEntry

/** Parsers for adb text output. All of them tolerate `\r\n`, blank lines and adb daemon noise. */
object AdbParsers {

    private val DEVICE_PROPERTY = Regex("""\b(product|model|device|transport_id|usb):(\S+)""")
    private val USER_INFO = Regex("""UserInfo\{(\d+):(.*):([0-9a-fA-F]+)}(\s+running)?""")
    private val GETPROP_LINE = Regex("""^\[(.+?)]: \[(.*)]$""")

    /** Removes CR and adb's own status lines (`* daemon started successfully`, version mismatch warnings). */
    fun lines(output: String): List<String> {
        return output
            .replace("\r", "")
            .lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("* ") || it.startsWith("adb server") || it.startsWith("adb: ") && it.contains("daemon") }
    }

    /** Parses `adb devices -l`. The separator is a tab without `-l` and padded spaces with it. */
    fun parseDevices(output: String): List<AdbDevice> {
        return lines(output)
            .filterNot { it.startsWith("List of devices") }
            .mapNotNull { line ->
                val serial = line.substringBefore('\t').substringBefore(' ').trim()
                if (serial.isEmpty()) return@mapNotNull null
                val rest = line.substring(serial.length).trim()
                val properties = DEVICE_PROPERTY.findAll(rest).associate { it.groupValues[1] to it.groupValues[2] }
                AdbDevice(
                    serial = serial,
                    state = DeviceConnectionState.of(rest),
                    model = properties["model"],
                    product = properties["product"],
                    transportId = properties["transport_id"],
                )
            }
            .distinctBy { it.serial }
    }

    /** Parses `pm list packages [--show-versioncode]`. Invalid package names are skipped. */
    fun parsePackageList(output: String): List<PackageListEntry> {
        return lines(output)
            .filter { it.startsWith("package:") }
            .mapNotNull { line ->
                val tokens = line.removePrefix("package:").trim().split(Regex("\\s+"))
                val name = tokens.firstOrNull()?.takeIf { PackageNames.isValid(it) } ?: return@mapNotNull null
                val versionCode = tokens.firstOrNull { it.startsWith("versionCode:") }
                    ?.removePrefix("versionCode:")
                    ?.toLongOrNull()
                PackageListEntry(name, versionCode)
            }
            .distinctBy { it.packageName }
    }

    /** Parses `pm list users`: `UserInfo{0:Owner:c13} running`. */
    fun parseUsers(output: String): List<AndroidUser> {
        return lines(output).mapNotNull { line ->
            USER_INFO.find(line)?.let {
                AndroidUser(
                    id = it.groupValues[1].toInt(),
                    name = it.groupValues[2],
                    running = it.groupValues[4].isNotBlank(),
                )
            }
        }
    }

    /** Parses `getprop` without arguments: `[ro.product.model]: [Pixel 7]`. */
    fun parseGetprop(output: String): Map<String, String> {
        return lines(output)
            .mapNotNull { GETPROP_LINE.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
