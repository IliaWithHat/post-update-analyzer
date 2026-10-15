package com.ilia.postupdateanalyzer.adb.model

/** State of a package for one Android user. A package missing from the device entirely is represented by `null`. */
enum class PackageState(val title: String) {
    ENABLED("Enabled"),
    DISABLED("Disabled"),

    /** Not installed for this user, but the APK is still on the device (typical for removed system apps). */
    UNINSTALLED("Uninstalled"),
}
