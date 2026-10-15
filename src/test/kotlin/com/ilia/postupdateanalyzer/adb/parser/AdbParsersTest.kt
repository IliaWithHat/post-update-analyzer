package com.ilia.postupdateanalyzer.adb.parser

import com.ilia.postupdateanalyzer.adb.model.AdbDevice
import com.ilia.postupdateanalyzer.adb.model.AndroidUser
import com.ilia.postupdateanalyzer.adb.model.DeviceConnectionState
import com.ilia.postupdateanalyzer.adb.model.PackageListEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AdbParsersTest {

    @Nested
    inner class Devices {

        @Test
        fun `parses every device state from adb devices -l`() {
            val output = """
                * daemon not running; starting now at tcp:5037
                * daemon started successfully
                List of devices attached
                emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1
                R58M123ABC             unauthorized usb:1-1 transport_id:2
                0123456789ABCDEF       no permissions (missing udev rules? user is in the plugdev group); see [http://developer.android.com/tools/device.html] usb:1-2 transport_id:3
                192.168.1.5:5555       offline transport_id:4
                adb-R58M12-abc._adb-tls-connect._tcp device product:panther model:Pixel_7 device:panther transport_id:5
                ZY22ABC                recovery usb:1-3 transport_id:6
                ZY22DEF                authorizing usb:1-4 transport_id:7
                ZY22GHI                something-new transport_id:8
            """.trimIndent()

            val devices = AdbParsers.parseDevices(output)

            assertThat(devices.map { it.serial to it.state }).containsExactly(
                "emulator-5554" to DeviceConnectionState.DEVICE,
                "R58M123ABC" to DeviceConnectionState.UNAUTHORIZED,
                "0123456789ABCDEF" to DeviceConnectionState.NO_PERMISSIONS,
                "192.168.1.5:5555" to DeviceConnectionState.OFFLINE,
                "adb-R58M12-abc._adb-tls-connect._tcp" to DeviceConnectionState.DEVICE,
                "ZY22ABC" to DeviceConnectionState.RECOVERY,
                "ZY22DEF" to DeviceConnectionState.AUTHORIZING,
                "ZY22GHI" to DeviceConnectionState.UNKNOWN,
            )
            assertThat(devices[0].model).isEqualTo("sdk_gphone64_x86_64")
            assertThat(devices[0].transportId).isEqualTo("1")
            assertThat(devices[2].model).isNull()
            assertThat(devices[4].model).isEqualTo("Pixel_7")
        }

        @Test
        fun `parses the tab separated format without -l and windows line endings`() {
            val devices = AdbParsers.parseDevices("List of devices attached\r\nemulator-5554\tdevice\r\n\r\n")

            assertThat(devices).containsExactly(AdbDevice("emulator-5554", DeviceConnectionState.DEVICE))
        }

        @Test
        fun `empty list and version mismatch noise give no devices`() {
            val output = "adb server version (40) doesn't match this client (41); killing...\n* daemon started successfully\nList of devices attached\n\n"

            assertThat(AdbParsers.parseDevices(output)).isEmpty()
            assertThat(AdbParsers.parseDevices("")).isEmpty()
        }

        @Test
        fun `duplicate serials are reported once`() {
            val devices = AdbParsers.parseDevices("List of devices attached\nA\tdevice\nA\tdevice\n")

            assertThat(devices).hasSize(1)
        }
    }

    @Nested
    inner class Packages {

        @Test
        fun `parses package lines with and without version codes`() {
            val output = "package:com.android.settings versionCode:33\r\npackage:com.google.android.youtube versionCode:1545868784\npackage:android\n"

            assertThat(AdbParsers.parsePackageList(output)).containsExactly(
                PackageListEntry("com.android.settings", 33),
                PackageListEntry("com.google.android.youtube", 1545868784),
                PackageListEntry("android", null),
            )
        }

        @Test
        fun `skips invalid names, warnings and duplicates`() {
            val output = """
                WARNING: linker: libdvm.so has text relocations
                package:com.good.app
                package:com.good.app
                package:not_a_package
                package:com.bad..name
                package:com.bad;rm -rf /
                package:
                something else
            """.trimIndent()

            assertThat(AdbParsers.parsePackageList(output).map { it.packageName }).containsExactly("com.good.app")
        }

        @Test
        fun `empty output means no packages`() {
            assertThat(AdbParsers.parsePackageList("")).isEmpty()
        }
    }

    @Nested
    inner class Users {

        @Test
        fun `parses users including names with colons and spaces`() {
            val output = "Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{10:Work: profile:1030}\n\tUserInfo{150:Secure Folder:10001030} running\n"

            assertThat(AdbParsers.parseUsers(output)).containsExactly(
                AndroidUser(0, "Owner", running = true),
                AndroidUser(10, "Work: profile", running = false),
                AndroidUser(150, "Secure Folder", running = true),
            )
        }

        @Test
        fun `ignores unparseable user lines`() {
            assertThat(AdbParsers.parseUsers("Users:\n\tsomething strange\n")).isEmpty()
        }
    }

    @Test
    fun `parses getprop including empty values and brackets in values`() {
        val output = "[ro.serialno]: [R58M12345]\r\n[ro.product.model]: [SM-G973F]\n[ro.empty]: []\n[ro.odd]: [a [b] c]\ngarbage line\n"

        assertThat(AdbParsers.parseGetprop(output)).containsExactlyInAnyOrderEntriesOf(
            mapOf("ro.serialno" to "R58M12345", "ro.product.model" to "SM-G973F", "ro.empty" to "", "ro.odd" to "a [b] c"),
        )
    }
}
