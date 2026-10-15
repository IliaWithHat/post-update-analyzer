package com.ilia.postupdateanalyzer.support.process

import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.system.exitProcess

/**
 * A stand-in for Chrome/Edge, started as a real process by `AppWindowTest`: writes `pid=<its pid>` and its arguments
 * to the file in `-Dfake.args`, then stays "open" for `-Dfake.lifetime` milliseconds, like a window the user closes later.
 * With `-Dfake.wrapper=true` it acts like Brave's launch script: runs the "browser" as a child and waits for it.
 */
object FakeBrowserMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (System.getProperty("fake.wrapper") == "true") {
            val java = ProcessHandle.current().info().command().orElse("java")
            val properties = listOf("fake.args", "fake.lifetime").mapNotNull { key -> System.getProperty(key)?.let { "-D$key=$it" } }
            val browser = ProcessBuilder(listOf(java) + properties + listOf("-cp", System.getProperty("java.class.path"), FakeBrowserMain::class.java.name) + args)
                .inheritIO()
                .start()
            exitProcess(browser.waitFor())
        }
        System.getProperty("fake.args")?.let { Files.write(Path(it), listOf("pid=${ProcessHandle.current().pid()}") + args) }
        Thread.sleep(System.getProperty("fake.lifetime", "0").toLong())
    }
}
