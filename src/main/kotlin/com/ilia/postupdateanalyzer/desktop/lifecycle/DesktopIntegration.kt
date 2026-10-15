package com.ilia.postupdateanalyzer.desktop.lifecycle

import com.ilia.postupdateanalyzer.config.AppProperties
import com.ilia.postupdateanalyzer.desktop.browser.BrowserOpener
import com.ilia.postupdateanalyzer.desktop.window.AppWindow
import com.ilia.postupdateanalyzer.desktop.window.AppWindowLauncher
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.URI
import java.time.Duration
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.WindowConstants

/**
 * When started by double-click there is no console. The app opens in its own window (Chrome, Edge, Brave…
 * in app mode) and quits when that window is closed. Without such a browser it opens a normal browser tab and
 * shows a small control window instead: it tells the user that the app is running and is the obvious way to quit it.
 */
@Component
@ConditionalOnProperty("app.desktop.enabled", havingValue = "true")
class DesktopIntegration(private val shutdown: AppShutdown, private val properties: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    private var window: JFrame? = null

    @Volatile
    private var appWindow: AppWindow? = null

    @Volatile
    private var closing = false

    @EventListener
    fun onReady(event: ApplicationReadyEvent) {
        val port = (event.applicationContext as? WebServerApplicationContext)?.webServer?.port ?: return
        val url = URI("http://127.0.0.1:$port/")
        val opened = AppWindowLauncher(properties.dataDir).open(url)
        if (opened == null) {
            BrowserOpener.open(url)
            showControlWindow(url)
            return
        }
        appWindow = opened
        opened.whenClosed(
            QUICK_EXIT,
            onClosed = {
                if (!closing) {
                    log.info("The app window was closed")
                    shutdown.quit()
                }
            },
            onLost = {
                if (!closing) {
                    // A copy with the same profile was still running and took over, or the browser failed.
                    log.info("The app window's browser exited at once; showing the control window instead")
                    showControlWindow(url)
                }
            },
        )
    }

    @EventListener(ContextClosedEvent::class)
    fun onClose() {
        closing = true
        appWindow?.close()
        SwingUtilities.invokeLater { window?.dispose() }
    }

    private fun showControlWindow(url: URI) {
        SwingUtilities.invokeLater { window = createWindow(url) }
    }

    private fun createWindow(url: URI): JFrame {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val frame = JFrame("Post-Update Analyzer")
        javaClass.getResourceAsStream("/static/img/app-icon.png")?.use { stream ->
            runCatching { frame.iconImage = ImageIO.read(stream) }
        }

        val title = JLabel("Post-Update Analyzer is running").apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 3f)
        }
        val link = JLabel("<html><a href=''>$url</a></html>").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    BrowserOpener.open(url)
                }
            })
        }
        val hint = JLabel("Closing this window stops the app.")
        val open = JButton("Open in browser").apply { addActionListener { BrowserOpener.open(url) } }
        val quit = JButton("Quit").apply { addActionListener { shutdown.quit() } }

        val text = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(title)
            add(javax.swing.Box.createVerticalStrut(8))
            add(link)
            add(javax.swing.Box.createVerticalStrut(8))
            add(hint)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(open)
            add(quit)
        }
        frame.contentPane = JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(16, 20, 12, 20)
            add(text, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                frame.isVisible = false
                shutdown.quit()
            }
        })
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        return frame
    }

    private companion object {
        /** A browser start that hands over to a running copy ends within about a second. */
        val QUICK_EXIT: Duration = Duration.ofSeconds(5)
    }
}
