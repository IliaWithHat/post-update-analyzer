package com.ilia.postupdateanalyzer.device.model

import com.ilia.postupdateanalyzer.adb.model.AdbDevice
import com.ilia.postupdateanalyzer.adb.model.AdbStatus
import com.ilia.postupdateanalyzer.adb.model.PackageState
import java.time.Instant

/** A phone known to the app (stored in the database). */
data class Device(
    val id: Long,
    val serialNo: String,
    val manufacturer: String?,
    val model: String?,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
) {
    val displayName: String
        get() {
            val maker = manufacturer?.trim().orEmpty().replaceFirstChar { it.uppercase() }
            val name = model?.trim().orEmpty()
            val combined = when {
                maker.isEmpty() -> name
                name.startsWith(maker, ignoreCase = true) -> name
                else -> "$maker $name".trim()
            }
            return combined.ifEmpty { serialNo }
        }
}

/** Properties read with `getprop`. */
data class DeviceProps(val adbSerial: String, val raw: Map<String, String>) {
    val serialNo: String
        get() = listOf("ro.serialno", "ro.boot.serialno")
            .firstNotNullOfOrNull { key -> raw[key]?.trim()?.takeIf { it.isNotEmpty() && it != "unknown" } }
            ?: adbSerial

    val manufacturer: String? get() = raw["ro.product.manufacturer"]?.takeIf { it.isNotBlank() }
    val model: String? get() = raw["ro.product.model"]?.takeIf { it.isNotBlank() }
    val sdk: Int get() = raw["ro.build.version.sdk"]?.trim()?.toIntOrNull() ?: 0
    val release: String? get() = raw["ro.build.version.release"]
    val fingerprint: String? get() = raw["ro.build.fingerprint"]
    val displayId: String? get() = raw["ro.build.display.id"]
    val incremental: String? get() = raw["ro.build.version.incremental"]
    val securityPatch: String? get() = raw["ro.build.version.security_patch"]
}

/** One entry of `adb devices`, plus what we know about it. */
data class ConnectedDevice(val adb: AdbDevice, val props: DeviceProps?, val record: Device?, val error: String? = null)

data class KnownDevice(val device: Device, val snapshotCount: Int, val lastSnapshotAt: Instant?, val connected: Boolean)

data class DeviceScan(
    val adb: AdbStatus,
    val connected: List<ConnectedDevice>,
    val known: List<KnownDevice>,
    val error: String? = null,
)

/** A stored device that is connected right now. */
data class LiveDevice(val record: Device, val adbSerial: String, val props: DeviceProps)

data class DeviceUser(val id: Int, val name: String, val running: Boolean, val current: Boolean, val protected: Boolean)

/** State of one package as read from the phone right now. */
data class LivePackage(val packageName: String, val state: PackageState, val system: Boolean, val versionCode: Long?)
