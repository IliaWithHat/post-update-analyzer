package com.ilia.postupdateanalyzer.emulator.support

import com.ilia.postupdateanalyzer.device.service.DeviceService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real app (real adb, real SQLite) against a real Android emulator.
 * Run with `./gradlew emulatorTest`; skipped when Docker, KVM or adb are missing.
 */
@Tag("emulator")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class EmulatorTest {

    @Autowired
    lateinit var devices: DeviceService

    @Autowired
    lateinit var jdbc: JdbcClient

    lateinit var adb: HostAdb

    @BeforeEach
    fun prepareEmulator() {
        SharedEmulator.assumeAvailable()
        adb = SharedEmulator.adb
        listOf("action_log", "snapshot_package", "snapshot", "app_icon", "device", "app_setting").forEach {
            jdbc.sql("DELETE FROM $it").update()
        }
        // Every test starts from the stock state of the packages it plays with.
        TEST_PACKAGES.forEach(adb::reset)
        adb.adb("-s", SharedEmulator.serial, "uninstall", TRASH_APP)
    }

    /** Database id of the emulator (other phones may be connected to this computer too; they are never touched). */
    fun emulatorDeviceId(): Long {
        return devices.scan().connected.single { it.adb.serial == SharedEmulator.serial }.record!!.id
    }

    companion object {
        /** Harmless apps of the emulator image used as "vendor bloat". */
        const val EGG = "com.android.egg"
        const val LIVE_WALLPAPERS = "com.android.wallpaper.livepicker"
        const val DAYDREAMS = "com.android.dreams.basic"
        const val TRASH_APP = "com.ilia.pua.testtrash"
        val TEST_PACKAGES = listOf(EGG, LIVE_WALLPAPERS, DAYDREAMS)

        private val dataDir: Path = Files.createTempDirectory("pua-emulator-")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("app.data-dir") { dataDir.toString() }
            registry.add("app.adb-path") { SharedEmulator.adbPath?.toString() ?: "" }
        }
    }
}
