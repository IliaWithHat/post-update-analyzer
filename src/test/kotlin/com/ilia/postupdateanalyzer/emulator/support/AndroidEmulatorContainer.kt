package com.ilia.postupdateanalyzer.emulator.support

import com.github.dockerjava.api.model.Device
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * A real Android 13 emulator (Google APIs, x86_64) in Docker, like a Testcontainers module.
 * Needs Docker, `/dev/kvm` and the adb binary on this computer; tests are skipped otherwise.
 */
class AndroidEmulatorContainer : GenericContainer<AndroidEmulatorContainer>(DockerImageName.parse(IMAGE)) {
    init {
        withExposedPorts(ADB_PORT)
        withEnv("MEMORY", "4096")
        withEnv("CORES", "2")
        withCreateContainerCmdModifier { it.hostConfig!!.withDevices(Device.parse("/dev/kvm")) }
        // emulator-monitoring.sh prints this once `sys.boot_completed` is 1
        waitingFor(Wait.forLogMessage(".*ANDROID_READY.*", 1).withStartupTimeout(Duration.ofMinutes(8)))
        withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("emulator")).withSeparateOutputStreams())
    }

    /** Serial as seen by the host adb after `adb connect`. */
    val serial: String
        get() = "$host:${getMappedPort(ADB_PORT)}".replace("localhost", "127.0.0.1")

    companion object {
        const val IMAGE = "halimqarroum/docker-android:api-33"
        const val ADB_PORT = 5555
    }
}
