package com.ilia.postupdateanalyzer.desktop.browser

import com.ilia.postupdateanalyzer.platform.OperatingSystem
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.net.URI

object BrowserOpener {
    private val log = LoggerFactory.getLogger(javaClass)

    fun open(uri: URI, os: OperatingSystem = OperatingSystem.current()): Boolean {
        // xdg-open is more reliable than AWT on Linux desktops (Wayland, KDE); AWT is best on Windows/macOS.
        val attempts: List<() -> Boolean> = if (os == OperatingSystem.LINUX) listOf({ command(uri, os) }, { awt(uri) })
        else listOf({ awt(uri) }, { command(uri, os) })
        return attempts.any { attempt -> runCatching(attempt).getOrDefault(false) }.also {
            if (!it) log.warn("Could not open the browser. Open {} manually.", uri)
        }
    }

    private fun awt(uri: URI): Boolean {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false
        Desktop.getDesktop().browse(uri)
        return true
    }

    private fun command(uri: URI, os: OperatingSystem): Boolean {
        val command = when (os) {
            OperatingSystem.WINDOWS -> listOf("rundll32", "url.dll,FileProtocolHandler", uri.toString())
            OperatingSystem.MAC -> listOf("open", uri.toString())
            else -> listOf("xdg-open", uri.toString())
        }
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        return true
    }
}
