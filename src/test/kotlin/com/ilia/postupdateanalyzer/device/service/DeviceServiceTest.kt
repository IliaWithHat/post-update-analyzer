package com.ilia.postupdateanalyzer.device.service

import com.ilia.postupdateanalyzer.adb.model.AdbResult
import com.ilia.postupdateanalyzer.adb.model.AndroidUser
import com.ilia.postupdateanalyzer.adb.model.DeviceConnectionState
import com.ilia.postupdateanalyzer.adb.model.PackageState
import com.ilia.postupdateanalyzer.device.exception.DeviceNotConnectedException
import com.ilia.postupdateanalyzer.device.exception.NotFoundException
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotKind
import com.ilia.postupdateanalyzer.snapshot.service.SnapshotService
import com.ilia.postupdateanalyzer.support.IntegrationTest
import com.ilia.postupdateanalyzer.support.fake.FakeDevice
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class DeviceServiceTest : IntegrationTest() {

    @Autowired
    lateinit var devices: DeviceService

    @Autowired
    lateinit var reader: PackageStateReader

    @Autowired
    lateinit var snapshots: SnapshotService

    @Test
    fun `lists connected phones in every state and remembers authorized ones`() {
        phone("R58M12345")
        adb.connect(FakeDevice("UNAUTH1")).state = DeviceConnectionState.UNAUTHORIZED
        adb.connect(FakeDevice("OFFLINE1")).state = DeviceConnectionState.OFFLINE

        val scan = devices.scan()

        assertThat(scan.adb.available).isTrue()
        assertThat(scan.connected.map { it.adb.serial to it.adb.state }).containsExactlyInAnyOrder(
            "R58M12345" to DeviceConnectionState.DEVICE,
            "UNAUTH1" to DeviceConnectionState.UNAUTHORIZED,
            "OFFLINE1" to DeviceConnectionState.OFFLINE,
        )
        val authorized = scan.connected.single { it.adb.serial == "R58M12345" }
        assertThat(authorized.record!!.displayName).isEqualTo("Google Pixel 7")
        assertThat(authorized.props!!.sdk).isEqualTo(33)
        assertThat(scan.connected.single { it.adb.serial == "UNAUTH1" }.record).isNull()
        assertThat(scan.known.map { it.device.serialNo }).containsExactly("R58M12345")
        assertThat(scan.known.single().connected).isTrue()
    }

    @Test
    fun `without adb there is nothing to scan`() {
        phone()
        adbEnvironment.available = false

        val scan = devices.scan()

        assertThat(scan.adb.available).isFalse()
        assertThat(scan.connected).isEmpty()
        assertThat(adb.commands).isEmpty()
    }

    @Test
    fun `adb errors are reported, not thrown`() {
        adb.interceptors += { args -> if (args.first() == "devices") AdbResult(1, "", "cannot connect to daemon") else null }

        assertThat(devices.scan().error).contains("cannot connect to daemon")
    }

    @Test
    fun `a phone keeps its history when it reconnects over Wi-Fi with a different adb serial`() {
        val usb = phone("R58M12345", serialNo = "R58M12345")
        val id = devices.scan().connected.single().record!!.id
        snapshots.capture(id, 0, SnapshotKind.BEFORE_UPDATE, null)

        adb.devices.remove(usb.serial)
        adb.connect(FakeDevice("192.168.1.20:5555", serialNo = "R58M12345").add("com.android.settings"))

        val scan = devices.scan()
        assertThat(scan.known).hasSize(1)
        assertThat(scan.known.single().snapshotCount).isEqualTo(1)
        assertThat(devices.live(id).adbSerial).isEqualTo("192.168.1.20:5555")
    }

    @Test
    fun `phones without a serial number fall back to the adb serial`() {
        val phone = phone("emulator-5554")
        phone.props["ro.serialno"] = ""

        assertThat(devices.scan().connected.single().record!!.serialNo).isEqualTo("emulator-5554")
    }

    @Test
    fun `live fails with a clear message when the phone is gone`() {
        val phone = phone()
        val id = devices.scan().connected.single().record!!.id
        adb.devices.remove(phone.serial)

        assertThatThrownBy { devices.live(id) }.isInstanceOf(DeviceNotConnectedException::class.java)
            .hasMessageContaining("Google Pixel 7 is not connected")
        assertThatThrownBy { devices.get(12345) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `lists users and marks the ones adb cannot access`() {
        val phone = phone()
        phone.users += AndroidUser(10, "Work profile", running = true)
        phone.users += AndroidUser(150, "Secure Folder", running = false)
        phone.protectedUsers += 150
        phone.currentUser = 10
        val live = devices.live(devices.scan().connected.single().record!!.id)

        val users = devices.users(live)

        assertThat(users.map { Triple(it.id, it.current, it.protected) })
            .containsExactly(Triple(0, false, false), Triple(10, true, false), Triple(150, false, true))
    }

    @Test
    fun `reads package states like UAD-ng`() {
        val phone = phone()
            .add("com.vendor.disabled", PackageState.DISABLED)
            .add("com.vendor.removed", PackageState.UNINSTALLED)
            .add("com.vendor.removedx", PackageState.ENABLED)

        val all = reader.readAll(phone.serial, 0, 33)

        assertThat(all.getValue("com.vendor.disabled").state).isEqualTo(PackageState.DISABLED)
        assertThat(all.getValue("com.vendor.removed").state).isEqualTo(PackageState.UNINSTALLED)
        assertThat(all.getValue("com.android.chrome").state).isEqualTo(PackageState.ENABLED)
        assertThat(all.getValue("com.example.game").system).isFalse()
        // pm's filter matches substrings; the reader must not confuse com.vendor.removed with com.vendor.removedx
        assertThat(reader.readOne(phone.serial, 0, 33, "com.vendor.removed")!!.state).isEqualTo(PackageState.UNINSTALLED)
        assertThat(reader.readOne(phone.serial, 0, 33, "com.vendor.nothere")).isNull()
    }
}
