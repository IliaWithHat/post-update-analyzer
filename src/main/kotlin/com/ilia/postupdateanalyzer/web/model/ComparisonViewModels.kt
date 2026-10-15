package com.ilia.postupdateanalyzer.web.model

import com.ilia.postupdateanalyzer.adb.model.PackageAction
import com.ilia.postupdateanalyzer.adb.model.PackageState
import com.ilia.postupdateanalyzer.compare.model.ChangeType
import com.ilia.postupdateanalyzer.compare.model.Comparison
import com.ilia.postupdateanalyzer.compare.model.PackageDiff
import com.ilia.postupdateanalyzer.device.model.LiveDevice
import com.ilia.postupdateanalyzer.device.model.LivePackage

/** One table row: what changed between the snapshots, the state on the phone right now and the possible actions. */
data class AppRow(
    val diff: PackageDiff,
    /** The phone is connected, so [current] is meaningful. */
    val connected: Boolean,
    /** State on the phone right now; null + connected = the app is not on the phone. */
    val current: LivePackage?,
) {
    val id: String
        get() = "row-" + diff.packageName.replace('.', '-')

    val gone: Boolean
        get() = connected && current == null

    val userApp: Boolean
        get() = !(current?.system ?: diff.system)

    /** One button per action; only those that make sense for the current state. */
    val actions: List<PackageAction>
        get() = when (current?.state) {
            PackageState.ENABLED -> listOf(PackageAction.DISABLE, PackageAction.UNINSTALL)
            PackageState.DISABLED -> listOf(PackageAction.RESTORE, PackageAction.UNINSTALL)
            PackageState.UNINSTALLED -> listOf(PackageAction.RESTORE, PackageAction.DISABLE)
            null -> emptyList()
        }

    fun actionHint(action: PackageAction): String {
        return when (action) {
            PackageAction.UNINSTALL -> if (userApp) {
                "Removes this user-installed app completely. To get it back, reinstall it from the app store."
            } else {
                "Uninstalls the app for this user. The system keeps the APK, so it can be restored later."
            }
            PackageAction.DISABLE -> "Turns the app off. It stays installed and can be restored later."
            PackageAction.RESTORE -> if (current?.state == PackageState.DISABLED) "Enables the app again." else "Installs the app again from the copy kept by the system."
        }
    }
}

data class ComparisonPage(
    val comparison: Comparison,
    val rows: List<AppRow>,
    val live: LiveDevice?,
    /** Why the current state could not be read although the phone is connected. */
    val liveError: String?,
) {
    val changedTypes: List<ChangeType>
        get() = ChangeType.entries.filter { it.changed && comparison.counts.getValue(it) > 0 }
}
