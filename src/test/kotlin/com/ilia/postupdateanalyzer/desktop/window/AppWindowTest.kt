package com.ilia.postupdateanalyzer.desktop.window

import com.ilia.postupdateanalyzer.platform.OperatingSystem
import com.ilia.postupdateanalyzer.support.process.FakeBrowserMain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path

class AppWindowTest {

    @TempDir
    lateinit var tmp: Path

    private val home = Path("/home/me")
    private val url = URI("http://127.0.0.1:18765/")

    @Nested
    inner class FindingTheBrowser {

        private fun locator(os: OperatingSystem, env: Map<String, String>, vararg installed: Path): ChromiumLocator {
            return ChromiumLocator(env, os, home) { it in installed }
        }

        @Test
        fun `prefers Chrome over Brave whatever the PATH order`() {
            val env = mapOf("PATH" to "/opt/brave/bin:/opt/chrome/bin")

            val found = locator(OperatingSystem.LINUX, env, Path("/opt/brave/bin/brave-browser"), Path("/opt/chrome/bin/google-chrome")).locate()

            assertThat(found).isEqualTo(Path("/opt/chrome/bin/google-chrome"))
        }

        @Test
        fun `finds browsers in the usual folders when PATH lacks them`() {
            assertThat(locator(OperatingSystem.LINUX, mapOf("PATH" to "/home/me/bin"), Path("/snap/bin/chromium")).locate())
                .isEqualTo(Path("/snap/bin/chromium"))
            assertThat(locator(OperatingSystem.LINUX, emptyMap(), Path("/usr/bin/microsoft-edge")).locate())
                .isEqualTo(Path("/usr/bin/microsoft-edge"))
        }

        @Test
        fun `no Chromium-based browser, no app window`() {
            assertThat(locator(OperatingSystem.LINUX, mapOf("PATH" to "/usr/bin"), Path("/usr/bin/firefox")).locate()).isNull()
            assertThat(locator(OperatingSystem.WINDOWS, emptyMap()).locate()).isNull()
        }

        @Test
        fun `on Windows finds Edge, but takes Chrome when installed`() {
            val env = mapOf(
                "ProgramFiles" to "C:\\Program Files",
                "ProgramFiles(x86)" to "C:\\Program Files (x86)",
                "LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local",
            )
            val edge = Path("C:\\Program Files (x86)").resolve("Microsoft").resolve("Edge").resolve("Application").resolve("msedge.exe")
            val chrome = Path("C:\\Users\\me\\AppData\\Local").resolve("Google").resolve("Chrome").resolve("Application").resolve("chrome.exe")

            assertThat(locator(OperatingSystem.WINDOWS, env, edge).locate()).isEqualTo(edge)
            assertThat(locator(OperatingSystem.WINDOWS, env, edge, chrome).locate()).isEqualTo(chrome)
        }

        @Test
        fun `on macOS looks inside the app bundles`() {
            val brave = Path("/Applications/Brave Browser.app/Contents/MacOS/Brave Browser")

            assertThat(locator(OperatingSystem.MAC, emptyMap(), brave).locate()).isEqualTo(brave)
        }
    }

    @Nested
    inner class Command {

        private fun launcher(os: OperatingSystem): AppWindowLauncher {
            return AppWindowLauncher(tmp, os, home, locate = { null })
        }

        @Test
        fun `opens the page as an app window with its own profile`() {
            val profile = tmp.resolve("window")

            val command = launcher(OperatingSystem.LINUX).command(Path("/usr/bin/google-chrome"), url, profile)

            assertThat(command).containsExactly(
                "/usr/bin/google-chrome",
                "--app=$url",
                "--user-data-dir=$profile",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-sync",
                "--hide-crash-restore-bubble",
                "--password-store=basic",
                "--start-maximized",
            )
        }

        @Test
        fun `on Windows maximizes the window too and leaves the password store alone`() {
            val command = launcher(OperatingSystem.WINDOWS).command(Path("msedge.exe"), url, tmp)

            assertThat(command).contains("--start-maximized")
            assertThat(command).doesNotContain("--password-store=basic")
        }

        @Test
        fun `keeps the profile in the data folder, or in the snap's own folder for snap browsers`() {
            assertThat(launcher(OperatingSystem.LINUX).profileDir(Path("/usr/bin/brave-browser"))).isEqualTo(tmp.resolve("window"))
            assertThat(launcher(OperatingSystem.LINUX).profileDir(Path("/snap/bin/chromium")))
                .isEqualTo(Path("/home/me/snap/chromium/common/post-update-analyzer-window"))
        }
    }

    @Nested
    inner class Opening {

        private val java = ProcessHandle.current().info().command().orElse("java")
        private val argsFile get() = tmp.resolve("args.txt")

        /** Runs [FakeBrowserMain] instead of the browser; it stays open for [lifetime]. */
        private fun launcher(lifetime: Duration, browser: Path? = Path("/usr/bin/google-chrome"), wrapper: Boolean = false): AppWindowLauncher {
            return AppWindowLauncher(
                tmp, OperatingSystem.current(), home,
                locate = { browser },
                start = { command ->
                    ProcessBuilder(
                        listOf(java, "-Dfake.lifetime=${lifetime.toMillis()}", "-Dfake.args=$argsFile", "-Dfake.wrapper=$wrapper") +
                            listOf("-cp", System.getProperty("java.class.path"), FakeBrowserMain::class.java.name) + command.drop(1),
                    ).start()
                },
            )
        }

        private fun AppWindow.result(quickExit: Duration): CompletableFuture<String> {
            val result = CompletableFuture<String>()
            whenClosed(quickExit, onClosed = { result.complete("closed") }, onLost = { result.complete("lost") })
            return result
        }

        @Test
        fun `starts the browser with the app address marked as app window`() {
            val window = launcher(Duration.ZERO).open(url)!!
            window.result(Duration.ZERO).get(30, TimeUnit.SECONDS)

            assertThat(Files.readAllLines(argsFile)).contains("--app=http://127.0.0.1:18765/?window=app", "--user-data-dir=${tmp.resolve("window")}", "--start-maximized")

            Files.createDirectories(tmp.resolve("window"))
            launcher(Duration.ZERO).open(url)!!.result(Duration.ZERO).get(30, TimeUnit.SECONDS)
            assertThat(Files.readAllLines(argsFile)).contains("--start-maximized")
        }

        @Test
        fun `closing the window is reported once the browser exits`() {
            val window = launcher(Duration.ofMillis(1500)).open(url)!!

            assertThat(window.result(quickExit = Duration.ofMillis(500)).get(30, TimeUnit.SECONDS)).isEqualTo("closed")
            assertThat(window.isOpen).isFalse()
        }

        @Test
        fun `a browser that exits at once cannot be followed`() {
            val window = launcher(Duration.ZERO).open(url)!!

            assertThat(window.result(quickExit = Duration.ofSeconds(30)).get(30, TimeUnit.SECONDS)).isEqualTo("lost")
        }

        @Test
        fun `quitting the app closes the window`() {
            val window = launcher(Duration.ofMinutes(5)).open(url)!!
            val started = System.nanoTime()

            window.close()

            assertThat(window.isOpen).isFalse()
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10))
        }

        @Test
        @EnabledOnOs(OS.LINUX)
        fun `follows and closes a browser started by a launch script`() {
            val window = launcher(Duration.ofMinutes(5), wrapper = true).open(url)!!
            val result = window.result(quickExit = Duration.ZERO)
            await { Files.exists(argsFile) && Files.readAllLines(argsFile).isNotEmpty() }
            val browser = ProcessHandle.of(Files.readAllLines(argsFile).first().removePrefix("pid=").toLong()).orElseThrow()
            val started = System.nanoTime()

            // Asks the browser itself to stop: stopping only the script would leave the window open.
            window.close(timeout = Duration.ofSeconds(20))

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10))
            assertThat(browser.isAlive).isFalse()
            assertThat(window.isOpen).isFalse()
            assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo("closed")
        }

        private fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            while (!condition()) {
                check(System.nanoTime() < deadline) { "timed out" }
                Thread.sleep(50)
            }
        }

        @Test
        fun `without a usable browser the caller falls back to a browser tab`() {
            assertThat(launcher(Duration.ZERO, browser = null).open(url)).isNull()

            val broken = AppWindowLauncher(tmp, OperatingSystem.LINUX, home, locate = { Path("/usr/bin/chromium") }, start = { throw IOException("No such file") })
            assertThat(broken.open(url)).isNull()
        }
    }
}
