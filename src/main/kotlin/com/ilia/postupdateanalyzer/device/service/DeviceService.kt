package com.ilia.postupdateanalyzer.device.service

import com.ilia.postupdateanalyzer.adb.client.AdbClient
import com.ilia.postupdateanalyzer.adb.command.PackageCommands
import com.ilia.postupdateanalyzer.adb.environment.AdbEnvironment
import com.ilia.postupdateanalyzer.adb.exception.AdbException
import com.ilia.postupdateanalyzer.adb.model.AdbDevice
import com.ilia.postupdateanalyzer.adb.model.DeviceConnectionState
import com.ilia.postupdateanalyzer.device.exception.DeviceNotConnectedException
import com.ilia.postupdateanalyzer.device.exception.NotFoundException
import com.ilia.postupdateanalyzer.device.model.ConnectedDevice
import com.ilia.postupdateanalyzer.device.model.Device
import com.ilia.postupdateanalyzer.device.model.DeviceProps
import com.ilia.postupdateanalyzer.device.model.DeviceScan
import com.ilia.postupdateanalyzer.device.model.DeviceUser
import com.ilia.postupdateanalyzer.device.model.KnownDevice
import com.ilia.postupdateanalyzer.device.model.LiveDevice
import com.ilia.postupdateanalyzer.device.repository.DeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

@Service
class DeviceService(
    private val adb: AdbClient,
    private val adbEnvironment: AdbEnvironment,
    private val devices: DeviceRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** getprop results per connection; the transport id changes whenever the phone reconnects or reboots. */
    private val propsCache = ConcurrentHashMap<String, DeviceProps>()

    fun scan(): DeviceScan {
        val status = adbEnvironment.status()
        var error: String? = null
        val connected = if (!status.available) {
            emptyList()
        } else {
            try {
                adb.devices().map(::describe)
            } catch (e: AdbException) {
                error = e.message
                emptyList()
            }
        }
        val connectedIds = connected.mapNotNull { it.record?.id }.toSet()
        val stats = devices.snapshotStats()
        val known = devices.findAll().map {
            val stat = stats[it.id]
            KnownDevice(it, stat?.count ?: 0, stat?.lastAt, it.id in connectedIds)
        }
        return DeviceScan(status, connected, known, error)
    }

    fun get(deviceId: Long): Device {
        return devices.findById(deviceId) ?: throw NotFoundException("Device $deviceId not found")
    }

    /** Finds the adb connection of a stored device. */
    fun live(deviceId: Long): LiveDevice {
        val record = get(deviceId)
        return liveOrNull(record) ?: throw DeviceNotConnectedException(
            "${record.displayName} is not connected. Connect it with USB debugging enabled and try again.",
        )
    }

    fun liveOrNull(record: Device): LiveDevice? {
        if (!adbEnvironment.status().available) return null
        val connected = runCatching { adb.devices() }.getOrElse { return null }
        return connected
            .filter { it.state == DeviceConnectionState.DEVICE }
            .firstNotNullOfOrNull { device ->
                val props = runCatching { props(device) }.getOrNull()
                props?.takeIf { it.serialNo == record.serialNo }?.let { LiveDevice(record, device.serial, it) }
            }
    }

    /** Android users of the phone. A user whose packages cannot be listed (e.g. Secure Folder) is "protected". */
    fun users(device: LiveDevice): List<DeviceUser> {
        val current = adb.currentUser(device.adbSerial) ?: 0
        val users = runCatching { adb.listUsers(device.adbSerial) }.getOrDefault(emptyList())
        if (users.isEmpty() || device.props.sdk < 21) {
            return listOf(DeviceUser(0, "Owner", running = true, current = true, protected = false))
        }
        return users.map { user ->
            val protected = users.size > 1 && runCatching {
                adb.listPackages(device.adbSerial, PackageCommands.userFlag(device.props.sdk, user.id), listOf("-s"))
            }.isFailure
            DeviceUser(user.id, user.name, user.running, current = user.id == current, protected = protected)
        }
    }

    private fun describe(device: AdbDevice): ConnectedDevice {
        if (device.state != DeviceConnectionState.DEVICE) return ConnectedDevice(device, null, null)
        return try {
            val props = props(device)
            val record = devices.upsert(props.serialNo, props.manufacturer, props.model, clock.instant())
            ConnectedDevice(device, props, record)
        } catch (e: AdbException) {
            log.warn("Cannot read properties of {}: {}", device.serial, e.message)
            ConnectedDevice(device, null, null, e.message)
        }
    }

    private fun props(device: AdbDevice): DeviceProps {
        val key = "${device.serial}|${device.transportId}"
        propsCache[key]?.let { return it }
        val props = DeviceProps(device.serial, adb.getprops(device.serial))
        if (device.transportId != null) propsCache[key] = props
        return props
    }

    /** Fresh properties (not cached), used when a snapshot records the build. */
    fun freshProps(device: LiveDevice): DeviceProps {
        return DeviceProps(device.adbSerial, adb.getprops(device.adbSerial))
    }
}
