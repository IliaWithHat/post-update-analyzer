package com.ilia.postupdateanalyzer.adb.command

import com.ilia.postupdateanalyzer.adb.exception.UnsupportedActionException
import com.ilia.postupdateanalyzer.adb.model.PackageState

/**
 * Shell commands that move a package one step from its current state towards a wanted state.
 * Port of UAD-ng `apply_pkg_state_commands` (crates/uad-core/src/sync.rs), without `pm clear`
 * so that user data is never wiped.
 *
 * The first command of every list is the one that changes the state; the rest are best-effort.
 */
object PackageCommands {

    const val MIN_SUPPORTED_SDK = 19
    const val MIN_DISABLE_SDK = 23

    /**
     * @return commands for one step; empty when [current] already equals [wanted].
     *   Uninstalled -> Disabled takes two steps (restore, then disable).
     */
    fun step(packageName: String, current: PackageState, wanted: PackageState, sdk: Int, userId: Int): List<String> {
        PackageNames.requireValid(packageName)
        if (sdk < MIN_SUPPORTED_SDK) {
            throw UnsupportedActionException("Android API $sdk is too old; API $MIN_SUPPORTED_SDK (Android 4.4) or newer is required.")
        }
        val commands = when (wanted) {
            PackageState.ENABLED -> when (current) {
                PackageState.DISABLED -> listOf("pm enable")
                PackageState.UNINSTALLED -> restoreCommands(sdk)
                PackageState.ENABLED -> emptyList()
            }

            PackageState.DISABLED -> when (current) {
                PackageState.ENABLED -> {
                    if (sdk < MIN_DISABLE_SDK) {
                        throw UnsupportedActionException("Disabling apps over ADB needs Android 6.0 (API 23) or newer.")
                    }
                    listOf("pm disable-user", "am force-stop")
                }
                // An uninstalled app has to come back before it can be disabled.
                PackageState.UNINSTALLED -> restoreCommands(sdk)
                PackageState.DISABLED -> emptyList()
            }

            PackageState.UNINSTALLED -> when (current) {
                PackageState.ENABLED, PackageState.DISABLED -> when {
                    sdk >= 23 -> listOf("pm uninstall")
                    sdk >= 21 -> listOf("pm hide")
                    else -> listOf("pm block")
                }

                PackageState.UNINSTALLED -> emptyList()
            }
        }
        val user = userFlag(sdk, userId)
        return commands.map { "$it$user $packageName" }
    }

    private fun restoreCommands(sdk: Int): List<String> {
        return when {
            sdk >= 23 -> listOf("cmd package install-existing")
            sdk >= 21 -> listOf("pm unhide")
            else -> listOf("pm unblock")
        }
    }

    /** `--user` is understood since Android 5.0 (API 21). */
    fun userFlag(sdk: Int, userId: Int): String {
        return if (sdk >= 21) " --user $userId" else ""
    }
}
