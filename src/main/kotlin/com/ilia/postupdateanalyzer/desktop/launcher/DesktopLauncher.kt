package com.ilia.postupdateanalyzer.desktop.launcher

import com.ilia.postupdateanalyzer.PostUpdateAnalyzerApplication
import com.ilia.postupdateanalyzer.desktop.browser.BrowserOpener
import com.ilia.postupdateanalyzer.desktop.window.AppWindowLauncher
import com.ilia.postupdateanalyzer.platform.OperatingSystem
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextClosedEvent
import java.awt.GraphicsEnvironment
import java.net.URI
import java.nio.file.Files
import javax.swing.JOptionPane
import kotlin.io.path.Path
import kotlin.system.exitProcess

/**
 * Entry point for double-click starts: picks the data folder and port, keeps a single instance,
 * then starts Spring. Pass `--no-gui` to run without browser and control window (e.g. on a server).
 */
object DesktopLauncher {

    const val PREFERRED_PORT = 18765

    fun launch(args: Array<String>) {
        val gui = "--no-gui" !in args && !GraphicsEnvironment.isHeadless()
        val dataDir = option(args, "app.data-dir")?.let { Path(it) }
            ?: DataDirectories.default(OperatingSystem.current(), System.getenv(), Path(System.getProperty("user.home")))

        try {
            Files.createDirectories(dataDir)
        } catch (e: Exception) {
            fail(gui, "Cannot create the data folder $dataDir: ${e.message}")
        }

        val lock = InstanceLock.acquire(dataDir)
        if (lock == null) {
            // Already running: open another window of the running app instead of starting twice.
            val port = InstanceLock.readPort(dataDir)
            if (port != null && gui) {
                val url = URI("http://127.0.0.1:$port/")
                if (AppWindowLauncher(dataDir).open(url) == null) BrowserOpener.open(url)
            }
            if (port == null) fail(gui, "Post-Update Analyzer is already starting. Try again in a few seconds.")
            exitProcess(0)
        }

        val port = option(args, "server.port")?.toIntOrNull() ?: Ports.pick(PREFERRED_PORT)
        // System properties win over application.yaml, command-line arguments still win over them.
        System.setProperty("app.data-dir", dataDir.toString())
        System.setProperty("app.desktop.enabled", gui.toString())
        System.setProperty("server.port", port.toString())
        System.setProperty("logging.file.name", dataDir.resolve("logs").resolve("app.log").toString())

        try {
            SpringApplicationBuilder(PostUpdateAnalyzerApplication::class.java)
                .headless(!gui)
                .listeners(
                    ApplicationListener<WebServerInitializedEvent> { lock.writePort(it.webServer.port) },
                    ApplicationListener<ContextClosedEvent> { lock.close() },
                )
                .run(*args.filter { it != "--no-gui" }.toTypedArray())
        } catch (e: Throwable) {
            lock.close()
            fail(gui, "Post-Update Analyzer could not start: ${rootMessage(e)}\n\nDetails: ${dataDir.resolve("logs").resolve("app.log")}")
        }
    }

    private fun option(args: Array<String>, name: String): String? {
        return args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
            ?: System.getProperty(name)
    }

    private fun rootMessage(e: Throwable): String {
        var cause: Throwable = e
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return cause.message ?: cause.javaClass.simpleName
    }

    private fun fail(gui: Boolean, message: String): Nothing {
        System.err.println(message)
        if (gui) {
            runCatching { JOptionPane.showMessageDialog(null, message, "Post-Update Analyzer", JOptionPane.ERROR_MESSAGE) }
        }
        exitProcess(1)
    }
}
