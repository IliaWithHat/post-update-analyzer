package com.ilia.postupdateanalyzer.compare.model

import com.ilia.postupdateanalyzer.adb.model.PackageState

/** What happened to a package between two snapshots. Order = display order. */
enum class ChangeType(val title: String, val description: String, val cssClass: String) {
    INSTALLED("New app", "Was not on the phone before, now installed.", "danger"),
    RESTORED("Reinstalled", "Was uninstalled before, now installed again.", "danger"),
    RE_ENABLED("Re-enabled", "Was disabled before, now enabled again.", "danger"),
    DISABLED("Disabled", "Was enabled before, now disabled.", "info"),
    UNINSTALLED("Uninstalled", "Was installed before, now uninstalled for this user.", "info"),
    REMOVED("Removed", "Was on the phone before, now completely gone.", "info"),
    ADDED_INACTIVE("Added (not installed)", "New on the phone, but not installed for this user.", "info"),
    UNCHANGED("Unchanged", "Same state in both snapshots.", "muted"),
    ;

    val changed: Boolean
        get() = this != UNCHANGED

    companion object {
        /** `null` = package not on the phone. */
        fun of(before: PackageState?, after: PackageState?): ChangeType {
            return when {
                before == after -> UNCHANGED
                before == null -> if (after == PackageState.UNINSTALLED) ADDED_INACTIVE else INSTALLED
                after == null -> REMOVED
                before == PackageState.UNINSTALLED -> RESTORED
                after == PackageState.UNINSTALLED -> UNINSTALLED
                after == PackageState.ENABLED -> RE_ENABLED
                else -> DISABLED
            }
        }
    }
}
