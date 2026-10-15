package com.ilia.postupdateanalyzer.desktop.window

import com.ilia.postupdateanalyzer.platform.OperatingSystem
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Finds a Chromium-based browser (Chrome, Chromium, Brave, Edge, Vivaldi). Only these can show a page as an
 * app window without tabs and address bar (`--app=URL`); Firefox cannot. Edge is part of Windows 10/11.
 */
class ChromiumLocator(
    private val env: Map<String, String> = System.getenv(),
    private val os: OperatingSystem = OperatingSystem.current(),
    private val userHome: Path = Path(System.getProperty("user.home")),
    private val isRunnable: (Path) -> Boolean = { path ->
        runCatching { path.isRegularFile() && (os == OperatingSystem.WINDOWS || path.isExecutable()) }.getOrDefault(false)
    },
) {

    fun locate(): Path? {
        return candidates().firstOrNull(isRunnable)
    }

    /** In order of preference: by browser first, then by location. */
    fun candidates(): List<Path> {
        return when (os) {
            OperatingSystem.WINDOWS -> {
                val roots = listOf("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")
                    .mapNotNull { variable -> env[variable]?.takeIf { it.isNotBlank() }?.let { Path(it) } }
                WINDOWS_APPS.flatMap { app -> roots.map { root -> app.fold(root, Path::resolve) } }
            }

            OperatingSystem.MAC -> {
                val roots = listOf(Path("/Applications"), userHome.resolve("Applications"))
                MAC_APPS.flatMap { (bundle, binary) -> roots.map { it.resolve(bundle).resolve("Contents/MacOS").resolve(binary) } }
            }

            else -> {
                val pathDirs = env["PATH"].orEmpty().split(':').filter { it.isNotBlank() }
                    .mapNotNull { runCatching { Path(it) }.getOrNull() }
                val dirs = (pathDirs + listOf("/usr/local/bin", "/usr/bin", "/snap/bin").map { Path(it) }).distinct()
                LINUX_NAMES.flatMap { name -> dirs.map { it.resolve(name) } }
            }
        }.distinct()
    }

    private companion object {
        val LINUX_NAMES = listOf(
            "google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "brave-browser", "brave",
            "microsoft-edge", "microsoft-edge-stable", "vivaldi", "vivaldi-stable",
        )
        val WINDOWS_APPS = listOf(
            listOf("Google", "Chrome", "Application", "chrome.exe"),
            listOf("BraveSoftware", "Brave-Browser", "Application", "brave.exe"),
            listOf("Chromium", "Application", "chrome.exe"),
            listOf("Vivaldi", "Application", "vivaldi.exe"),
            listOf("Microsoft", "Edge", "Application", "msedge.exe"),
        )
        val MAC_APPS = listOf(
            "Google Chrome.app" to "Google Chrome",
            "Chromium.app" to "Chromium",
            "Brave Browser.app" to "Brave Browser",
            "Microsoft Edge.app" to "Microsoft Edge",
            "Vivaldi.app" to "Vivaldi",
        )
    }
}
