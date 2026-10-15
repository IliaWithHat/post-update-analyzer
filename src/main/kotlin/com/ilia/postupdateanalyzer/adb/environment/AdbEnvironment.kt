package com.ilia.postupdateanalyzer.adb.environment

import com.ilia.postupdateanalyzer.adb.exception.AdbNotFoundException
import com.ilia.postupdateanalyzer.adb.executor.ProcessAdbExecutor
import com.ilia.postupdateanalyzer.adb.model.AdbStatus

/** Knows where adb is. The result is cached until Settings change or [status] is called with `refresh`. */
interface AdbEnvironment {
    fun status(refresh: Boolean = false): AdbStatus

    /** Saves a user-chosen adb path (blank = auto-detect) and returns the new status. */
    fun configure(path: String?): AdbStatus

    /** Command prefix for [ProcessAdbExecutor]. */
    fun command(): List<String> {
        return listOf((status().location ?: throw AdbNotFoundException()).path.toString())
    }
}
