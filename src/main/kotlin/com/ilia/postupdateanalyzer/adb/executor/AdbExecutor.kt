package com.ilia.postupdateanalyzer.adb.executor

import com.ilia.postupdateanalyzer.adb.model.AdbResult
import java.time.Duration

/** Runs one `adb` invocation. Implementations: [ProcessAdbExecutor] (real binary) and an in-memory fake in tests. */
fun interface AdbExecutor {
    /**
     * @param args arguments after `adb`, e.g. `["-s", "emulator-5554", "shell", "pm list packages"]`
     * @throws AdbNotFoundException if no adb binary is available
     */
    fun execute(args: List<String>, timeout: Duration): AdbResult
}
