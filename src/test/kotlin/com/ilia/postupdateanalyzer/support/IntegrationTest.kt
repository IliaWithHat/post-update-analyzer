package com.ilia.postupdateanalyzer.support

import com.ilia.postupdateanalyzer.support.fake.FakeAdbEnvironment
import com.ilia.postupdateanalyzer.support.fake.FakeAdbExecutor
import com.ilia.postupdateanalyzer.support.fake.FakeDevice
import com.ilia.postupdateanalyzer.support.fake.TickingClock
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path

/** Full application with a simulated phone and a fresh SQLite database (cleared before every test). */
@SpringBootTest
@Import(FakeAdbTestConfiguration::class)
abstract class IntegrationTest {

    @Autowired
    lateinit var adb: FakeAdbExecutor

    @Autowired
    lateinit var adbEnvironment: FakeAdbEnvironment

    @Autowired
    lateinit var jdbc: JdbcClient

    @Autowired
    lateinit var clock: TickingClock

    @BeforeEach
    fun resetState() {
        adb.reset()
        adbEnvironment.available = true
        adbEnvironment.configuredPath = null
        clock.reset()
        listOf("action_log", "snapshot_package", "snapshot", "app_icon", "device", "app_setting").forEach {
            jdbc.sql("DELETE FROM $it").update()
        }
        jdbc.sql("DELETE FROM sqlite_sequence").update()
    }

    /** A connected phone with a few typical apps. */
    fun phone(serial: String = "R58M12345", serialNo: String = serial): FakeDevice {
        return adb.connect(
            FakeDevice(serial, serialNo)
                .add("com.android.settings", label = "Settings")
                .add("com.android.systemui", label = "System UI")
                .add("com.android.launcher3", label = "Launcher")
                .add("com.android.chrome", label = "Chrome")
                .add("com.facebook.appmanager", label = "Facebook App Manager")
                .add("com.example.game", label = "Some Game", system = false),
        )
    }

    companion object {
        val dataDir: Path = Files.createTempDirectory("pua-test-")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("app.data-dir") { dataDir.toString() }
        }
    }
}
