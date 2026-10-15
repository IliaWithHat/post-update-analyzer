package com.ilia.postupdateanalyzer.emulator.support

import com.ilia.postupdateanalyzer.adb.executor.ProcessAdbExecutor
import com.ilia.postupdateanalyzer.adb.model.AdbResult
import com.ilia.postupdateanalyzer.adb.parser.AdbParsers
import java.nio.file.Path
import java.time.Duration

/** Runs the host adb directly (outside the app) to set up and check the emulator, like a person with a terminal. */
class HostAdb(val path: Path, private val serial: String) {
    private val executor = ProcessAdbExecutor { listOf(path.toString()) }

    fun adb(vararg args: String, timeout: Duration = Duration.ofMinutes(2)): AdbResult {
        return executor.execute(args.toList(), timeout)
    }

    fun shell(command: String): String {
        val result = adb("-s", serial, "shell", command)
        check(result.exitCode == 0) { "adb shell $command failed: ${result.output}" }
        return result.stdout
    }

    fun connect() {
        val deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos()
        while (System.nanoTime() < deadline) {
            adb("connect", serial, timeout = Duration.ofSeconds(20))
            val state = adb("-s", serial, "get-state", timeout = Duration.ofSeconds(10)).stdout.trim()
            val booted = state == "device" && adb("-s", serial, "shell", "getprop sys.boot_completed").stdout.trim() == "1"
            if (booted) return
            Thread.sleep(2000)
        }
        error("The emulator at $serial did not come online")
    }

    fun disconnect() {
        adb("disconnect", serial)
    }

    /** null = not on the phone */
    fun state(packageName: String, user: Int = 0): String? {
        fun listed(flags: String): Boolean {
            return AdbParsers.parsePackageList(shell("pm list packages $flags --user $user $packageName"))
                .any { it.packageName == packageName }
        }
        return when {
            listed("-d") -> "DISABLED"
            listed("") -> "ENABLED"
            listed("-u") -> "UNINSTALLED"
            else -> null
        }
    }

    fun install(apk: Path) {
        val result = adb("-s", serial, "install", "-r", apk.toString())
        check(result.stdout.contains("Success")) { "install failed: ${result.output}" }
    }

    /** Puts a system package back to installed + enabled. */
    fun reset(packageName: String) {
        shell("cmd package install-existing --user 0 $packageName")
        shell("pm enable --user 0 $packageName")
    }
}
