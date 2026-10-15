package com.ilia.postupdateanalyzer.adb.parser

import com.ilia.postupdateanalyzer.adb.command.PackageNames
import com.ilia.postupdateanalyzer.adb.model.AdbResult

/** Decides whether a device command failed and turns cryptic errors into advice. Based on UAD-ng `sync.rs`. */
object AdbErrors {

    private val FAILURE_MARKERS = listOf("Error", "Failure", "Exception", "Unknown command", "not found")

    private val DEVICE_GONE_MARKERS = listOf(
        "no devices/emulators found",
        "device offline",
        "device unauthorized",
        "device still authorizing",
        "error: closed",
        "no permissions",
        "insufficient permissions",
        "failed to get feature set",
    )
    private val DEVICE_NOT_FOUND = Regex("""device '[^']*' not found""")

    /** A line of a query result that is an error, not data (data lines look like `package:...` or `[prop]: [...]`). */
    private val QUERY_ERROR_LINE = Regex("""^(Error|Failure|Exception|java\.|/system/bin/sh:|Unknown command)""")

    /**
     * For read-only queries (`pm list packages`, `getprop`, ...). Their output can legitimately contain words like
     * "Error" (e.g. a package `com.foo.ErrorReporter`), so only whole error lines or a non-zero exit code count.
     */
    fun queryFailure(result: AdbResult, command: String): String? {
        if (result.timedOut) return "The phone did not answer in time while running: $command"
        val errorLine = AdbParsers.lines(result.output).any { QUERY_ERROR_LINE.containsMatchIn(it.trim()) || it.contains("Exception occurred") }
        if (result.exitCode == 0 && !errorLine) return null
        return friendly(result.output, command)
    }

    /** For commands that change something; old phones always exit with 0, so the output text is checked too. */
    fun failure(result: AdbResult, command: String): String? {
        if (result.timedOut) return "The phone did not answer in time while running: $command"
        val output = result.output
        // Success messages repeat the package name, which may itself contain e.g. "Error" (com.foo.ErrorReporter).
        val withoutPackage = command.substringAfterLast(' ').takeIf { PackageNames.isValid(it) }
            ?.let { output.replace(it, "") } ?: output
        val failed = result.exitCode != 0 || FAILURE_MARKERS.any { withoutPackage.contains(it) }
        if (!failed) return null
        return friendly(output, command)
    }

    /** True when adb itself could not reach the phone (as opposed to the command failing on the phone). */
    fun isDeviceGone(result: AdbResult): Boolean {
        if (result.succeeded) return false
        // adb's own errors are lowercase ("error: device offline"); errors from tools on the phone say "Error:".
        val text = result.stderr.ifBlank { result.stdout }
        if (!text.startsWith("adb: ") && !text.startsWith("error: ")) return false
        return DEVICE_NOT_FOUND.containsMatchIn(text) || DEVICE_GONE_MARKERS.any { text.contains(it, ignoreCase = true) }
    }

    fun friendly(output: String, command: String): String {
        val text = output.trim().ifEmpty { "no output" }
        return when {
            text.contains("DELETE_FAILED_USER_RESTRICTED") ->
                "The manufacturer blocks uninstalling this app (Samsung Knox or similar). Try disabling it instead. ($text)"

            text.contains("NOT_INSTALLED_FOR_USER") || text.contains("not installed for") ->
                "The app is not installed for this user. It may belong to another user profile. ($text)"

            text.contains("DELETE_FAILED_DEVICE_POLICY_MANAGER") ->
                "The app is managed by a device policy (work profile / MDM). Ask your IT administrator. ($text)"

            text.contains("Shell cannot change component state") || text.contains("Permission denied") ->
                "Android does not allow ADB to change this app. ($text)"

            text.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
                "A newer version of this app is already on the phone. ($text)"

            text.contains("INSTALL_FAILED") ->
                "Android refused to install the app. ($text)"

            else -> "$command -> $text"
        }
    }
}
