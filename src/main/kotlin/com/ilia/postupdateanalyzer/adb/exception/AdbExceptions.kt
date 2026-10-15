package com.ilia.postupdateanalyzer.adb.exception

import com.ilia.postupdateanalyzer.adb.model.AdbResult

open class AdbException(message: String, val result: AdbResult? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)

class AdbNotFoundException(message: String = "ADB was not found. Install Android platform-tools and set the path in Settings.") :
    AdbException(message)

/** The phone is not connected, not authorized or went offline. */
class DeviceUnavailableException(message: String, result: AdbResult? = null) : AdbException(message, result)

class AdbCommandException(message: String, result: AdbResult? = null) : AdbException(message, result)
