package com.ilia.postupdateanalyzer.adb.model

import java.nio.file.Path

data class AdbLocation(val path: Path, val source: String, val version: String)

data class AdbLookup(
    val location: AdbLocation?,
    /** Problems found on the way, e.g. a configured path that does not exist. */
    val problems: List<String>,
    val searched: List<Path>,
)

data class AdbStatus(
    val location: AdbLocation?,
    val configuredPath: String?,
    val problems: List<String>,
    val searched: List<Path>,
) {
    val available: Boolean
        get() = location != null
}
