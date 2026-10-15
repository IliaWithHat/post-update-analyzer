package com.ilia.postupdateanalyzer.desktop.lifecycle

import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/** Stops the app (Quit button in the browser or in the control window, or the app window was closed). */
@Component
class AppShutdown(private val context: ConfigurableApplicationContext) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val quitting = AtomicBoolean()

    /** Exits shortly after, so the HTTP response of the Quit button can still be sent. Later calls do nothing. */
    fun quit() {
        if (!quitting.compareAndSet(false, true)) return
        Thread.ofPlatform().name("app-shutdown").start {
            Thread.sleep(500)
            log.info("Quitting on user request")
            val code = SpringApplication.exit(context)
            exitProcess(code)
        }
    }
}
