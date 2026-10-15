package com.ilia.postupdateanalyzer.adb.client

import com.ilia.postupdateanalyzer.adb.command.PackageNames
import com.ilia.postupdateanalyzer.adb.exception.AdbCommandException
import com.ilia.postupdateanalyzer.adb.exception.DeviceUnavailableException
import com.ilia.postupdateanalyzer.adb.executor.AdbExecutor
import com.ilia.postupdateanalyzer.adb.model.AdbDevice
import com.ilia.postupdateanalyzer.adb.model.AdbResult
import com.ilia.postupdateanalyzer.adb.model.AndroidUser
import com.ilia.postupdateanalyzer.adb.model.PackageListEntry
import com.ilia.postupdateanalyzer.adb.parser.AdbErrors
import com.ilia.postupdateanalyzer.adb.parser.AdbParsers
import java.nio.file.Path
import java.time.Duration

/**
 * Typed wrapper around the adb commands this app needs. Commands that run on the phone go through
 * [shell]; the remote shell receives one string, so every package name is validated before use.
 */
class AdbClient(
    private val executor: AdbExecutor,
    private val timeout: Duration = Duration.ofSeconds(60),
    private val longTimeout: Duration = Duration.ofMinutes(5),
) {

    fun devices(): List<AdbDevice> {
        val result = executor.execute(listOf("devices", "-l"), timeout)
        if (!result.succeeded) throw AdbCommandException("adb devices failed: ${result.output}", result)
        return AdbParsers.parseDevices(result.stdout)
    }

    fun shell(serial: String, command: String, timeout: Duration = this.timeout): AdbResult {
        val result = executor.execute(listOf("-s", serial, "shell", command), timeout)
        if (AdbErrors.isDeviceGone(result)) {
            throw DeviceUnavailableException("The phone ($serial) is not available: ${result.output}", result)
        }
        return result
    }

    /** Runs a read-only shell command and fails if the phone reports an error. */
    fun shellChecked(serial: String, command: String, timeout: Duration = this.timeout): AdbResult {
        val result = shell(serial, command, timeout)
        AdbErrors.queryFailure(result, command)?.let { throw AdbCommandException(it, result) }
        return result
    }

    fun getprops(serial: String): Map<String, String> {
        return AdbParsers.parseGetprop(shellChecked(serial, "getprop").stdout)
    }

    fun listUsers(serial: String): List<AndroidUser> {
        return AdbParsers.parseUsers(shellChecked(serial, "pm list users").stdout)
    }

    fun currentUser(serial: String): Int? {
        return runCatching { shellChecked(serial, "am get-current-user").stdout.trim().toIntOrNull() }.getOrNull()
    }

    /**
     * `pm list packages` with the given flags (`-u`, `-d`, `-e`, `-s`, `-3`, `--show-versioncode`).
     * @param filter optional substring filter understood by pm; callers must compare names exactly.
     */
    fun listPackages(serial: String, userFlag: String, flags: List<String>, filter: String? = null): List<PackageListEntry> {
        filter?.let { PackageNames.requireValid(it) }
        val command = buildString {
            append("pm list packages")
            flags.forEach { append(' ').append(it) }
            append(userFlag)
            filter?.let { append(' ').append(it) }
        }
        return AdbParsers.parsePackageList(shellChecked(serial, command).stdout)
    }

    /** Copies a file to the phone (used for the metadata helper). */
    fun push(serial: String, local: Path, remote: String): AdbResult {
        val result = executor.execute(listOf("-s", serial, "push", local.toString(), remote), longTimeout)
        if (AdbErrors.isDeviceGone(result)) {
            throw DeviceUnavailableException("The phone is not available: ${result.output}", result)
        }
        return result
    }
}
