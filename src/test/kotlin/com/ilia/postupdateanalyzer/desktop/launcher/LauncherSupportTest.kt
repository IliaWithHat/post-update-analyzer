package com.ilia.postupdateanalyzer.desktop.launcher

import com.ilia.postupdateanalyzer.platform.OperatingSystem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.Path

class LauncherSupportTest {

    @TempDir
    lateinit var tmp: Path

    private val home = Path("/home/me")

    @Test
    fun `data folder per operating system`() {
        assertThat(DataDirectories.default(OperatingSystem.WINDOWS, mapOf("LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local"), home))
            .isEqualTo(Path("C:\\Users\\me\\AppData\\Local").resolve("PostUpdateAnalyzer"))
        assertThat(DataDirectories.default(OperatingSystem.WINDOWS, emptyMap(), home))
            .isEqualTo(home.resolve("AppData").resolve("Local").resolve("PostUpdateAnalyzer"))
        assertThat(DataDirectories.default(OperatingSystem.LINUX, emptyMap(), home))
            .isEqualTo(Path("/home/me/.local/share/post-update-analyzer"))
        assertThat(DataDirectories.default(OperatingSystem.LINUX, mapOf("XDG_DATA_HOME" to "/data/me"), home))
            .isEqualTo(Path("/data/me/post-update-analyzer"))
        // relative XDG paths are invalid per spec and ignored
        assertThat(DataDirectories.default(OperatingSystem.LINUX, mapOf("XDG_DATA_HOME" to "relative"), home))
            .isEqualTo(Path("/home/me/.local/share/post-update-analyzer"))
        assertThat(DataDirectories.default(OperatingSystem.MAC, emptyMap(), home))
            .isEqualTo(Path("/home/me/Library/Application Support/PostUpdateAnalyzer"))
    }

    @Test
    fun `only one instance can hold the lock, and the port is shared`() {
        val first = InstanceLock.acquire(tmp)!!
        first.writePort(18765)

        assertThat(InstanceLock.acquire(tmp)).isNull()
        assertThat(InstanceLock.readPort(tmp)).isEqualTo(18765)

        first.close()
        assertThat(InstanceLock.readPort(tmp)).isNull()
        InstanceLock.acquire(tmp)!!.close()
    }

    @Test
    fun `lock creates a missing data folder`() {
        val lock = InstanceLock.acquire(tmp.resolve("new").resolve("folder"))

        assertThat(lock).isNotNull()
        lock!!.close()
    }

    @Test
    fun `uses the preferred port when free, otherwise lets the server choose`() {
        ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { busy ->
            assertThat(Ports.pick(busy.localPort)).isZero()
        }
        val free = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertThat(Ports.pick(free)).isEqualTo(free)
    }
}
