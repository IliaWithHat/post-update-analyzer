package com.ilia.postupdateanalyzer.adb.model

enum class PackageAction(val title: String, val target: PackageState) {
    UNINSTALL("Uninstall", PackageState.UNINSTALLED),
    DISABLE("Disable", PackageState.DISABLED),
    RESTORE("Restore", PackageState.ENABLED),
}
