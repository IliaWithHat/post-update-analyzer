package com.ilia.postupdateanalyzer.emulator

import com.ilia.postupdateanalyzer.desktop.lifecycle.AppShutdown
import com.ilia.postupdateanalyzer.emulator.support.EmulatorTest
import com.ilia.postupdateanalyzer.emulator.support.SharedEmulator
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.nio.file.Path

/** The whole user journey in a real browser against a real Android system. */
class EmulatorUiTest : EmulatorTest() {

    @LocalServerPort
    var port: Int = 0

    @MockitoBean
    lateinit var shutdown: AppShutdown

    private lateinit var context: BrowserContext
    private lateinit var page: Page
    private val dialogs = mutableListOf<String>()
    private var acceptNextDialog = false

    private val slow = LocatorAssertions.ContainsTextOptions().setTimeout(120_000.0)

    @BeforeEach
    fun openBrowser() {
        context = browser.newContext(Browser.NewContextOptions().setViewportSize(1400, 1000))
        page = context.newPage()
        page.setDefaultTimeout(60_000.0)
        page.onDialog { dialog ->
            dialogs += dialog.message()
            if (acceptNextDialog) {
                acceptNextDialog = false
                dialog.accept()
            } else {
                dialog.dismiss()
            }
        }
    }

    @AfterEach
    fun closeBrowser() {
        if (::page.isInitialized) {
            page.screenshot(Page.ScreenshotOptions().setPath(SCREENSHOTS.resolve("emulator-${System.nanoTime()}.png")).setFullPage(true))
            context.close()
        }
    }

    private fun row(pkg: String): Locator {
        return page.locator("tr[data-package='$pkg']")
    }

    private fun click(pkg: String, action: String) {
        row(pkg).locator("button[value=$action]").click()
    }

    private fun currentState(pkg: String): Locator {
        return row(pkg).locator(".current .state")
    }

    private fun takeSnapshot(kind: String) {
        val rows = page.locator("#snapshot-table tbody tr")
        val count = rows.count()
        page.locator("input[name=kind][value=$kind]").check()
        page.locator("#take-snapshot").click()
        assertThat(rows).hasCount(count + 1, LocatorAssertions.HasCountOptions().setTimeout(120_000.0))
        assertThat(page.locator("#toasts .toast").last()).containsText("Saved", slow)
    }

    @Test
    fun `user debloats, the vendor update undoes it, the user fixes it with one click per app`() {
        // The user had already removed the Easter egg and disabled live wallpapers.
        adb.shell("pm uninstall --user 0 $EGG")
        adb.shell("pm disable-user --user 0 $LIVE_WALLPAPERS")

        page.navigate("http://127.0.0.1:$port/")
        page.locator(".device-card[data-serial='${SharedEmulator.serial}'] a.open-device").click()
        assertThat(page.locator("#capture")).isVisible()
        takeSnapshot("BEFORE_UPDATE")

        // The vendor update reinstalls and re-enables apps, disables one and installs a new app.
        adb.shell("cmd package install-existing --user 0 $EGG")
        adb.shell("pm enable --user 0 $LIVE_WALLPAPERS")
        adb.shell("pm disable-user --user 0 $DAYDREAMS")
        adb.install(SharedEmulator.testApk("test-trash-v1.apk"))

        page.reload()
        takeSnapshot("AFTER_UPDATE")
        page.locator("#compare-button").click()

        // Only the four changes are shown, with real names and real icons.
        val visible = page.locator("tr.app-row:visible")
        assertThat(visible).hasCount(4)
        assertThat(row(EGG)).containsText("Reinstalled")
        assertThat(row(LIVE_WALLPAPERS)).containsText("Re-enabled")
        assertThat(row(DAYDREAMS)).containsText("Disabled")
        assertThat(row(TRASH_APP)).containsText("New app")
        assertThat(row(TRASH_APP)).containsText("Free Games Trash")
        assertThat(row("com.android.settings")).isHidden()
        val iconWidth = row(EGG).locator("img.app-icon").evaluate("img => img.complete ? img.naturalWidth : -1")
        Assertions.assertThat(iconWidth).isEqualTo(96)

        page.locator("#show-unchanged").check()
        assertThat(row("com.android.settings")).isVisible()
        assertThat(row("com.android.settings")).containsText("Settings")
        page.locator("#show-unchanged").uncheck()

        // One click per app, no dialogs.
        click(EGG, "UNINSTALL")
        assertThat(currentState(EGG)).hasText("Uninstalled", LocatorAssertions.HasTextOptions().setTimeout(60_000.0))
        click(LIVE_WALLPAPERS, "DISABLE")
        assertThat(currentState(LIVE_WALLPAPERS)).hasText("Disabled", LocatorAssertions.HasTextOptions().setTimeout(60_000.0))
        click(TRASH_APP, "UNINSTALL")
        assertThat(currentState(TRASH_APP)).hasText("Not on phone", LocatorAssertions.HasTextOptions().setTimeout(60_000.0))
        Assertions.assertThat(dialogs).isEmpty()

        // The phone really changed.
        Assertions.assertThat(adb.state(EGG)).isEqualTo("UNINSTALLED")
        Assertions.assertThat(adb.state(LIVE_WALLPAPERS)).isEqualTo("DISABLED")
        Assertions.assertThat(adb.state(TRASH_APP)).isNull()
        Assertions.assertThat(adb.state(DAYDREAMS)).isEqualTo("DISABLED")

        // Changed my mind about the egg.
        click(EGG, "RESTORE")
        assertThat(currentState(EGG)).hasText("Enabled", LocatorAssertions.HasTextOptions().setTimeout(60_000.0))
        Assertions.assertThat(adb.state(EGG)).isEqualTo("ENABLED")

        // Everything is in the history.
        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("History")).click()
        assertThat(page.locator(".history-group")).hasCount(1)
        val history = page.locator("#history-groups tbody tr")
        assertThat(history).hasCount(4)
        assertThat(history.first()).containsText("Restore")
        assertThat(page.locator("#history-groups")).containsText("Free Games Trash")
    }

    companion object {
        private lateinit var playwright: Playwright
        private lateinit var browser: Browser
        val SCREENSHOTS: Path = Path.of("build", "ui-screenshots")

        @JvmStatic
        @BeforeAll
        fun startBrowser() {
            playwright = Playwright.create()
            browser = playwright.chromium().launch()
        }

        @JvmStatic
        @AfterAll
        fun stopBrowser() {
            browser.close()
            playwright.close()
        }
    }
}
