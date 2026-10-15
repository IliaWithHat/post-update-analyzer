package com.ilia.postupdateanalyzer.adb.environment

import com.ilia.postupdateanalyzer.platform.OperatingSystem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AdbLocatorTest {

    @TempDir
    lateinit var tmp: Path

    private val working = mutableSetOf<Path>()

    private fun fakeAdb(dir: Path, name: String = "adb", works: Boolean = true): Path {
        Files.createDirectories(dir)
        val file = dir.resolve(name)
        Files.writeString(file, "#!/bin/sh\n")
        file.toFile().setExecutable(true)
        if (works) working.add(file.toAbsolutePath())
        return file
    }

    private fun locator(env: Map<String, String>, os: OperatingSystem = OperatingSystem.LINUX): AdbLocator {
        return AdbLocator(env, os, tmp.resolve("home")) { path -> if (path.toAbsolutePath() in working) "Android Debug Bridge version 1.0.41" else null }
    }

    @Test
    fun `configured file wins over everything else`() {
        val configured = fakeAdb(tmp.resolve("custom"))
        fakeAdb(tmp.resolve("bin"))

        val lookup = locator(mapOf("PATH" to tmp.resolve("bin").toString())).locate(configured.toString())

        assertThat(lookup.location!!.path).isEqualTo(configured.toAbsolutePath())
        assertThat(lookup.location.source).isEqualTo("Settings")
        assertThat(lookup.problems).isEmpty()
    }

    @Test
    fun `configured folder is accepted (platform-tools directory)`() {
        val adb = fakeAdb(tmp.resolve("platform-tools"))

        val lookup = locator(emptyMap()).locate(tmp.resolve("platform-tools").toString())

        assertThat(lookup.location!!.path).isEqualTo(adb.toAbsolutePath())
    }

    @Test
    fun `broken configured path is reported and auto-detection continues`() {
        val onPath = fakeAdb(tmp.resolve("bin"))

        val lookup = locator(mapOf("PATH" to tmp.resolve("bin").toString())).locate("/does/not/exist/adb")

        assertThat(lookup.location!!.path).isEqualTo(onPath)
        assertThat(lookup.problems).anyMatch { it.contains("Settings does not work") }
    }

    @Test
    fun `finds adb on PATH, skipping folders where it does not run`() {
        fakeAdb(tmp.resolve("broken"), works = false)
        val good = fakeAdb(tmp.resolve("good"))

        val path = listOf(tmp.resolve("empty"), tmp.resolve("broken"), tmp.resolve("good")).joinToString(":")
        val lookup = locator(mapOf("PATH" to path)).locate(null)

        assertThat(lookup.location!!.path).isEqualTo(good)
        assertThat(lookup.location.source).isEqualTo("PATH")
        assertThat(lookup.problems).anyMatch { it.contains("does not run") }
    }

    @Test
    fun `uses ADB variable, then ANDROID_HOME`() {
        val fromVariable = fakeAdb(tmp.resolve("var"))
        val fromSdk = fakeAdb(tmp.resolve("sdk").resolve("platform-tools"))

        assertThat(locator(mapOf("ADB" to fromVariable.toString(), "ANDROID_HOME" to tmp.resolve("sdk").toString())).locate(null).location!!.path)
            .isEqualTo(fromVariable)
        assertThat(locator(mapOf("ANDROID_HOME" to tmp.resolve("sdk").toString())).locate(null).location!!.path)
            .isEqualTo(fromSdk)
    }

    @Test
    fun `finds the Android Studio SDK in the home folder`() {
        val studio = fakeAdb(tmp.resolve("home").resolve("Android").resolve("Sdk").resolve("platform-tools"))

        assertThat(locator(emptyMap()).locate(null).location!!.path).isEqualTo(studio)
    }

    @Test
    fun `on Windows looks for adb exe, uses semicolons in PATH and LOCALAPPDATA`() {
        val exe = fakeAdb(tmp.resolve("pt"), name = "adb.exe")
        fakeAdb(tmp.resolve("pt"), name = "adb") // must be ignored on Windows

        val lookup = locator(mapOf("Path" to "${tmp.resolve("nothing")};${tmp.resolve("pt")}"), OperatingSystem.WINDOWS).locate(null)
        assertThat(lookup.location!!.path).isEqualTo(exe)

        val local = fakeAdb(tmp.resolve("local").resolve("Android").resolve("Sdk").resolve("platform-tools"), name = "adb.exe")
        assertThat(locator(mapOf("LOCALAPPDATA" to tmp.resolve("local").toString()), OperatingSystem.WINDOWS).locate(null).location!!.path)
            .isEqualTo(local)
    }

    @Test
    fun `nothing found lists the searched places`() {
        val lookup = locator(mapOf("PATH" to tmp.resolve("bin").toString())).locate(null)

        assertThat(lookup.location).isNull()
        assertThat(lookup.searched).contains(tmp.resolve("bin").resolve("adb"))
    }
}
