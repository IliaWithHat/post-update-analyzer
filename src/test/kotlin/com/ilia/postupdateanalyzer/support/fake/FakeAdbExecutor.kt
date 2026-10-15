package com.ilia.postupdateanalyzer.support.fake

import com.ilia.postupdateanalyzer.adb.executor.AdbExecutor
import com.ilia.postupdateanalyzer.adb.model.AdbResult
import com.ilia.postupdateanalyzer.adb.model.DeviceConnectionState
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory replacement for the adb binary. */
class FakeAdbExecutor : AdbExecutor {
    val devices: MutableMap<String, FakeDevice> = java.util.concurrent.ConcurrentHashMap()
    val commands = CopyOnWriteArrayList<List<String>>()

    /** Return a result to short-circuit a command (e.g. simulate a timeout); null lets the fake handle it. */
    val interceptors = CopyOnWriteArrayList<(List<String>) -> AdbResult?>()

    fun reset() {
        devices.clear()
        commands.clear()
        interceptors.clear()
    }

    fun connect(device: FakeDevice): FakeDevice {
        devices[device.serial] = device
        return device
    }

    /** Shell commands sent to a device, without the `-s serial shell` prefix. */
    fun shellCommands(serial: String): List<String> {
        return commands.filter { it.size >= 4 && it[0] == "-s" && it[1] == serial && it[2] == "shell" }.map { it[3] }
    }

    override fun execute(args: List<String>, timeout: Duration): AdbResult {
        commands += args
        interceptors.firstNotNullOfOrNull { it(args) }?.let { return it }
        if (args == listOf("version")) return FakeDevice.ok("Android Debug Bridge version 1.0.41\nVersion 36.0.0-fake")
        if (args.firstOrNull() == "devices") {
            val lines = devices.values.joinToString("\n") { d ->
                val state = d.state.adbName
                val details = if (d.state == DeviceConnectionState.DEVICE) " product:panther model:${d.props["ro.product.model"]!!.replace(' ', '_')} device:panther" else ""
                "${d.serial.padEnd(22)} $state$details transport_id:${d.transportId}"
            }
            return FakeDevice.ok("List of devices attached\n$lines")
        }
        if (args.firstOrNull() != "-s") return AdbResult(1, "", "adb: usage: unknown command ${args.firstOrNull()}")
        val device = devices[args[1]] ?: return AdbResult(1, "", "adb: device '${args[1]}' not found")
        when (device.state) {
            DeviceConnectionState.DEVICE -> Unit
            DeviceConnectionState.UNAUTHORIZED -> return AdbResult(1, "", "error: device unauthorized.\nThis adb server's \$ADB_VENDOR_KEYS is not set")
            else -> return AdbResult(1, "", "error: device offline")
        }
        return when (args[2]) {
            "shell" -> device.shell(args[3])
            "push" -> {
                device.pushedFiles += args[4]
                FakeDevice.ok("${args[3]}: 1 file pushed, 0 skipped. 10.0 MB/s (4499 bytes in 0.000s)")
            }
            else -> AdbResult(1, "", "adb: unknown command ${args[2]}")
        }
    }
}
