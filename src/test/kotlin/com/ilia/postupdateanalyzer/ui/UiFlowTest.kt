package com.ilia.postupdateanalyzer.ui

import com.ilia.postupdateanalyzer.adb.model.DeviceConnectionState
import com.ilia.postupdateanalyzer.adb.model.PackageState
import com.ilia.postupdateanalyzer.desktop.browser.ExternalBrowser
import com.ilia.postupdateanalyzer.desktop.lifecycle.AppShutdown
import com.ilia.postupdateanalyzer.support.IntegrationTest
import com.ilia.postupdateanalyzer.support.fake.FakeAdbEnvironment
import com.ilia.postupdateanalyzer.support.fake.FakeDevice
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.LocatorAssertions
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.nio.file.Path

/** Clicks through the real pages in Chromium, like a user would, against a simulated phone. */
@Tag("ui")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UiFlowTest : IntegrationTest() {

    @LocalServerPort
    var port: Int = 0

    @MockitoBean
    lateinit var shutdown: AppShutdown

    @MockitoBean
    lateinit var externalBrowser: ExternalBrowser

    private lateinit var context: BrowserContext
    private lateinit var page: Page
    private val dialogs = mutableListOf<String>()

    /** Accept the next browser confirmation instead of cancelling it. */
    private var acceptNextDialog = false

    private val base get() = "http://127.0.0.1:$port"

    @BeforeEach
    fun openBrowser() {
        context = browser.newContext(Browser.NewContextOptions().setViewportSize(1400, 1000))
        page = context.newPage()
        page.setDefaultTimeout(15_000.0)
        // With a listener Playwright no longer dismisses dialogs itself, so the listener must answer every one.
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
    fun closeBrowser(info: TestInfo) {
        page.screenshot(Page.ScreenshotOptions().setPath(SCREENSHOTS.resolve("${info.testMethod.get().name}.png")).setFullPage(true))
        context.close()
    }

    private fun row(packageName: String): Locator {
        return page.locator("tr[data-package='$packageName']")
    }

    private fun button(packageName: String, action: String): Locator {
        return row(packageName).locator("button[value=$action]")
    }

    private fun takeSnapshot(kind: String) {
        val rows = page.locator("#snapshot-table tbody tr")
        val before = rows.count()
        page.locator("input[name=kind][value=$kind]").check()
        page.locator("#take-snapshot").click()
        assertThat(rows).hasCount(before + 1)
        assertThat(page.locator("#toasts .toast").last()).containsText("snapshot with")
    }

    /** Phone where the user had removed vendor apps; the update brings them back. */
    private fun debloatedPhone(): FakeDevice {
        return phone()
            .add("com.vendor.store", PackageState.UNINSTALLED, label = "Vendor Store")
            .add("com.vendor.cloud", PackageState.DISABLED, label = "Vendor Cloud")
    }

    private fun vendorUpdate(phone: FakeDevice) {
        phone.buildFingerprint("google/panther/panther:14/UP1A/1:user/release-keys", "UP1A.231005.007")
        phone.set("com.vendor.store", PackageState.ENABLED)
        phone.set("com.vendor.cloud", PackageState.ENABLED)
        phone.add("com.vendor.trash", label = "Free Games", system = false)
        phone.packages.getValue("com.android.chrome").versionCode = 2
    }

    @Test
    fun `before and after snapshot, compare, and fix the phone with one click per app`() {
        val phone = debloatedPhone()

        page.navigate(base)
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open")).click()
        assertThat(page.locator("h1")).hasText("Google Pixel 7")
        takeSnapshot("BEFORE_UPDATE")

        vendorUpdate(phone)
        page.reload()
        assertThat(page.locator("input[name=kind][value=AFTER_UPDATE]")).isChecked()
        takeSnapshot("AFTER_UPDATE")
        assertThat(page.locator("#snapshot-table tbody tr")).hasCount(2)

        page.locator("#compare-button").click()
        assertThat(page.locator("h1")).containsText("Changes on")

        // only the changes are visible at first
        val visibleRows = page.locator("tr.app-row:visible")
        assertThat(visibleRows).hasCount(3)
        assertThat(row("com.vendor.store")).containsText("Reinstalled")
        assertThat(row("com.vendor.cloud")).containsText("Re-enabled")
        assertThat(row("com.vendor.trash")).containsText("New app")
        assertThat(row("com.vendor.trash")).containsText("Free Games")
        val iconWidth = row("com.vendor.store").locator("img.app-icon").evaluate("img => img.complete ? img.naturalWidth : -1")
        Assertions.assertThat(iconWidth).isEqualTo(16)
        assertThat(row("com.android.chrome")).isHidden()
        assertThat(page.locator("#shown-count")).hasText("Showing 3 of 9 apps")

        // the checkbox shows unchanged apps (Chrome only got a new version)
        page.locator("#show-unchanged").check()
        assertThat(visibleRows).hasCount(9)
        assertThat(row("com.android.chrome")).containsText("updated")
        page.locator("#show-unchanged").uncheck()
        assertThat(visibleRows).hasCount(3)

        // filters
        page.locator("#search").fill("cloud")
        assertThat(visibleRows).hasCount(1)
        page.locator("#search").fill("")
        page.locator("#app-kind").selectOption("user")
        assertThat(visibleRows).hasCount(1)
        assertThat(row("com.vendor.trash")).isVisible()
        page.locator("#app-kind").selectOption("all")
        page.locator("#filters input[name=type][value=INSTALLED]").uncheck()
        assertThat(visibleRows).hasCount(2)
        page.locator("#filters input[name=type][value=INSTALLED]").check()

        // one click = one action, no dialog
        button("com.vendor.store", "UNINSTALL").click()
        assertThat(row("com.vendor.store").locator(".current .state")).hasText("Uninstalled")
        assertThat(page.locator("#toasts")).containsText("Uninstall Vendor Store: Uninstall done.")
        assertThat(button("com.vendor.store", "RESTORE")).isVisible()

        button("com.vendor.cloud", "DISABLE").click()
        assertThat(row("com.vendor.cloud").locator(".current .state")).hasText("Disabled")

        button("com.vendor.trash", "UNINSTALL").click()
        assertThat(row("com.vendor.trash").locator(".current .state")).hasText("Not on phone")
        assertThat(row("com.vendor.trash").locator("button")).hasCount(0)
        assertThat(row("com.vendor.trash")).containsText("reinstall it from the app store")

        // the replaced rows still follow the filters
        assertThat(visibleRows).hasCount(3)
        Assertions.assertThat(dialogs).isEmpty()
        Assertions.assertThat(phone.state("com.vendor.store")).isEqualTo(PackageState.UNINSTALLED)
        Assertions.assertThat(phone.state("com.vendor.cloud")).isEqualTo(PackageState.DISABLED)
        Assertions.assertThat(phone.state("com.vendor.trash")).isNull()

        // restore what was uninstalled by mistake
        button("com.vendor.store", "RESTORE").click()
        assertThat(row("com.vendor.store").locator(".current .state")).hasText("Enabled")
        Assertions.assertThat(phone.state("com.vendor.store")).isEqualTo(PackageState.ENABLED)

        val comparisonUrl = page.url().removePrefix(base)

        // the compared snapshots are kept for the history
        page.locator("h1 a").click()
        assertThat(page.locator("#snapshot-table .delete-snapshot")).hasCount(2)
        assertThat(page.locator("#snapshot-table .delete-snapshot:disabled")).hasCount(2)

        // everything is in the history, under the comparison it was run from
        page.getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName("History")).click()
        assertThat(page.locator(".history-group")).hasCount(1)
        assertThat(page.locator(".history-group a.open-comparison")).hasAttribute("href", comparisonUrl)
        val history = page.locator("#history-groups tbody tr")
        assertThat(history).hasCount(4)
        assertThat(history.first()).containsText("Restore")
        assertThat(history.last()).containsText("Vendor Store")
    }

    @Test
    fun `a failing action shows the reason`() {
        val phone = debloatedPhone()
        val (before, after) = snapshotsAroundUpdate(phone)
        phone.failures["pm uninstall"] = "Failure [DELETE_FAILED_USER_RESTRICTED]"

        page.navigate("$base/compare?before=$before&after=$after")
        button("com.vendor.store", "UNINSTALL").click()

        assertThat(page.locator("#toasts .toast.error")).containsText("Knox")
        assertThat(row("com.vendor.store").locator(".current .state")).hasText("Enabled")
    }

    @Test
    fun `without adb the user is guided to settings`() {
        adbEnvironment.available = false

        page.navigate(base)
        assertThat(page.locator("#adb-missing")).isVisible()
        page.locator("#adb-missing a[href='/settings']").click()

        assertThat(page.locator("#adb-not-found")).isVisible()
        page.locator("#adb-path").fill("/wrong/adb")
        page.locator("#save-adb").click()
        assertThat(page.locator("#toasts .toast.error")).containsText("does not work")

        page.locator("#adb-path").fill(FakeAdbEnvironment.GOOD_PATH)
        page.locator("#save-adb").click()
        assertThat(page.locator("#adb-found")).containsText("Android Debug Bridge")
    }

    @Test
    fun `in the app window links to other sites open in the normal browser, in a tab they open as usual`() {
        adbEnvironment.available = false
        val platformTools = URI("https://developer.android.com/tools/releases/platform-tools")
        doReturn(true).`when`(externalBrowser).open(platformTools)
        context.route("https://developer.android.com/**") { it.fulfill(Route.FulfillOptions().setBody("Platform-Tools")) }

        // The launcher opens the window at /?window=app; the flag must survive moving to other pages.
        page.navigate("$base/?window=app")
        page.locator("#adb-missing a[href='/settings']").click()
        page.locator("#adb-not-found a[href^='https://developer.android.com']").click()

        verify(externalBrowser, timeout(10_000)).open(platformTools)
        Assertions.assertThat(context.pages()).hasSize(1)

        val tab = context.newPage()
        tab.navigate("$base/settings")
        val popup = tab.waitForPopup { tab.locator("#adb-not-found a[href^='https://developer.android.com']").click() }
        Assertions.assertThat(popup.url()).isEqualTo(platformTools.toString())
        verify(externalBrowser).open(platformTools)
    }

    @Test
    fun `a phone plugged in later appears on its own, with a hint while unauthorized`() {
        page.navigate(base)
        assertThat(page.locator("#no-devices")).isVisible()

        val phone = adb.connect(FakeDevice("R58M99999").add("com.android.settings"))
        phone.state = DeviceConnectionState.UNAUTHORIZED
        assertThat(page.locator(".device-card")).containsText("Allow USB debugging", LocatorAssertions.ContainsTextOptions().setTimeout(15_000.0))

        phone.state = DeviceConnectionState.DEVICE
        assertThat(page.locator(".device-card a.open-device")).isVisible(LocatorAssertions.IsVisibleOptions().setTimeout(15_000.0))
    }

    @Test
    fun `deleting a snapshot asks first`() {
        val phone = debloatedPhone()
        snapshotsAroundUpdate(phone)
        page.navigate("$base/devices/1")
        assertThat(page.locator("#snapshot-table tbody tr")).hasCount(2)

        acceptNextDialog = true
        page.locator(".delete-snapshot").first().click()

        assertThat(page.locator("#snapshot-table tbody tr")).hasCount(1)
        assertThat(page.locator("#compare-form")).hasCount(0)
    }

    private fun snapshotsAroundUpdate(phone: FakeDevice): Pair<String, String> {
        page.navigate("$base/")
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Open")).click()
        takeSnapshot("BEFORE_UPDATE")
        vendorUpdate(phone)
        takeSnapshot("AFTER_UPDATE")
        val ids = page.locator("#snapshot-table tbody tr").all().map { it.getAttribute("data-snapshot-id")!! }
        return ids[1] to ids[0]
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
