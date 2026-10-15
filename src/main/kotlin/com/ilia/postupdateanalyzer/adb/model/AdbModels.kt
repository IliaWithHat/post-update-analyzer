package com.ilia.postupdateanalyzer.adb.model

data class AdbResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val durationMs: Long = 0,
) {
    /** stdout and stderr together; adb prints some errors to stdout and some to stderr. */
    val output: String
        get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")

    val succeeded: Boolean
        get() = exitCode == 0 && !timedOut
}

data class AdbDevice(
    val serial: String,
    val state: DeviceConnectionState,
    val model: String? = null,
    val product: String? = null,
    val transportId: String? = null,
)

data class AndroidUser(val id: Int, val name: String, val running: Boolean)

data class PackageListEntry(val packageName: String, val versionCode: Long?)
