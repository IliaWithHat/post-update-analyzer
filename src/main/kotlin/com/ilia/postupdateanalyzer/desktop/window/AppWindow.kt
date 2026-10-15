package com.ilia.postupdateanalyzer.desktop.window

import com.ilia.postupdateanalyzer.platform.OperatingSystem
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path

class AppWindow(private val process: Process, private val os: OperatingSystem) {

    private val startedAt = System.nanoTime()

    /**
     * Calls [onClosed] when the user closed the window. If the browser exits sooner than [quickExit], a browser
     * already running with the same profile showed the window (or the browser failed): the app cannot see when
     * that window closes, so [onLost] runs instead.
     */
    fun whenClosed(quickExit: Duration, onClosed: () -> Unit, onLost: () -> Unit) {
        process.onExit().thenRun {
            if (Duration.ofNanos(System.nanoTime() - startedAt) < quickExit) onLost() else onClosed()
        }
    }

    val isOpen: Boolean
        get() = process.isAlive

    /** Closes the window when the app quits another way (the Quit button). */
    fun close(timeout: Duration = Duration.ofSeconds(5)) {
        if (!process.isAlive) return
        val browser = browserProcess()
        if (os == OperatingSystem.WINDOWS) {
            // Process.destroy() kills at once on Windows; taskkill without /F asks the browser windows to close.
            runCatching {
                ProcessBuilder("taskkill", "/PID", browser.pid().toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                    .waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            }
        } else {
            browser.destroy() // SIGTERM: the browser closes its windows and saves its profile
        }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Some Linux launch scripts (Brave's) start the browser as a child and wait for it instead of replacing
     * themselves; stopping only the script would leave the window open. The browser's main process is the one
     * with a profile and without Chromium's `--type=` (renderer, gpu-process, zygote, …).
     */
    private fun browserProcess(): ProcessHandle {
        if (os != OperatingSystem.LINUX) return process.toHandle()
        return process.descendants()
            .filter { handle -> commandLine(handle).let { "--user-data-dir=" in it && "--type=" !in it } }
            .findFirst()
            .orElse(process.toHandle())
    }

    /**
     * Read from /proc: Chromium rewrites its process title into one space-separated string, which
     * `ProcessHandle.Info.arguments()` then reports as no arguments at all.
     */
    private fun commandLine(handle: ProcessHandle): String {
        return runCatching { String(Files.readAllBytes(Path("/proc/${handle.pid()}/cmdline"))).replace('\u0000', ' ') }
            .getOrDefault("")
    }
}
