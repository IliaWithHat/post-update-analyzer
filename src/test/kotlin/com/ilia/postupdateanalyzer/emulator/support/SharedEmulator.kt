package com.ilia.postupdateanalyzer.emulator.support

import com.ilia.postupdateanalyzer.adb.environment.AdbLocator
import com.ilia.postupdateanalyzer.adb.executor.ProcessAdbExecutor
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path

/** One emulator for the whole test run; Testcontainers (Ryuk) removes it when the JVM exits. */
object SharedEmulator {
    private val log = LoggerFactory.getLogger(javaClass)

    val adbPath: Path? by lazy {
        AdbLocator { path ->
            runCatching { ProcessAdbExecutor { listOf(path.toString()) }.execute(listOf("version"), Duration.ofSeconds(15)) }
                .getOrNull()?.takeIf { it.succeeded }?.stdout?.lines()?.firstOrNull()
        }.locate(System.getenv("PUA_TEST_ADB")).location?.path
    }

    private val container: AndroidEmulatorContainer by lazy {
        log.info("Starting Android emulator container (first run downloads ~2.7 GB)")
        AndroidEmulatorContainer().also { it.start() }
    }

    val serial: String
        get() = container.serial

    val adb: HostAdb by lazy {
        HostAdb(adbPath!!, container.serial).also { it.connect() }
    }

    /** Skips the calling test with a clear reason when this machine cannot run the emulator. */
    fun assumeAvailable() {
        val kvm = Path("/dev/kvm")
        assumeTrue(Files.isReadable(kvm) && Files.isWritable(kvm), "/dev/kvm is not accessible (KVM is needed to run the emulator)")
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false), "Docker is not available")
        assumeTrue(adbPath != null, "adb was not found on this computer (set PUA_TEST_ADB or add it to PATH)")
    }

    fun testApk(name: String): Path {
        val target = Files.createTempFile("pua-", "-$name")
        javaClass.getResourceAsStream("/apks/$name")!!.use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        return target
    }
}
