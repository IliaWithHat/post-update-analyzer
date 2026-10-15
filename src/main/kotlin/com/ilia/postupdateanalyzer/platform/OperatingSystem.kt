package com.ilia.postupdateanalyzer.platform

enum class OperatingSystem {
    WINDOWS, LINUX, MAC, OTHER;

    val adbFileName: String
        get() = if (this == WINDOWS) "adb.exe" else "adb"

    companion object {
        fun current(): OperatingSystem {
            val name = System.getProperty("os.name").lowercase()
            return when {
                name.startsWith("windows") -> WINDOWS
                name.contains("mac") || name.contains("darwin") -> MAC
                name.contains("linux") || name.contains("nux") -> LINUX
                else -> OTHER
            }
        }
    }
}
