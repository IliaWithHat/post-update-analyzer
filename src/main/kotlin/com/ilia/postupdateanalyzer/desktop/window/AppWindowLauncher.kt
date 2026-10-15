package com.ilia.postupdateanalyzer.desktop.window

import com.ilia.postupdateanalyzer.platform.OperatingSystem
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Opens the app in its own window, like Slack or other desktop web apps. The browser gets a separate profile,
 * so it runs as its own process next to the user's normal browser: that process ends exactly when the user
 * closes the window, which is how the app knows to quit.
 */
class AppWindowLauncher(
    private val dataDir: Path,
    private val os: OperatingSystem = OperatingSystem.current(),
    private val userHome: Path = Path(System.getProperty("user.home")),
    private val locate: () -> Path? = { ChromiumLocator(os = os, userHome = userHome).locate() },
    private val start: (List<String>) -> Process = { command ->
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    },
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Opens the home page of the app at [baseUrl].
     * @return null when there is no Chromium-based browser or it does not start; then use a normal browser tab.
     */
    fun open(baseUrl: URI): AppWindow? {
        val browser = locate()
        if (browser == null) {
            log.info("No Chrome, Edge, Brave or Chromium found, opening the default browser instead of an app window")
            return null
        }
        val profile = profileDir(browser)
        // app.js sees the marker and opens links to other sites in the user's normal browser.
        val url = URI(baseUrl.scheme, null, baseUrl.host, baseUrl.port, "/", MARKER, null)
        return try {
            AppWindow(start(command(browser, url, profile)), os)
                .also { log.info("Opened the app window with {}", browser) }
        } catch (e: Exception) {
            log.warn("Could not start {}: {}", browser, e.message)
            null
        }
    }

    /** Snap browsers may only write to their own folder, not to hidden folders like `~/.local/share`. */
    fun profileDir(browser: Path): Path {
        return if (os == OperatingSystem.LINUX && browser.startsWith(Path("/snap/bin"))) {
            userHome.resolve("snap").resolve(browser.fileName.toString().substringBefore('.'))
                .resolve("common").resolve("post-update-analyzer-window")
        } else {
            dataDir.resolve("window")
        }
    }

    fun command(browser: Path, url: URI, profile: Path): List<String> {
        return buildList {
            add(browser.toString())
            add("--app=$url")
            add("--user-data-dir=$profile")
            add("--no-first-run")
            add("--no-default-browser-check")
            add("--disable-sync")
            add("--hide-crash-restore-bubble")
            if (os == OperatingSystem.LINUX) add("--password-store=basic")
            add("--start-maximized")
        }
    }

    companion object {
        const val MARKER = "window=app"
    }
}
