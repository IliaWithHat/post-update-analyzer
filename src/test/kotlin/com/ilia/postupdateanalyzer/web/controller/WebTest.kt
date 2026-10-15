package com.ilia.postupdateanalyzer.web.controller

import com.ilia.postupdateanalyzer.action.model.ActionStatus
import com.ilia.postupdateanalyzer.action.repository.ActionLogRepository
import com.ilia.postupdateanalyzer.adb.model.DeviceConnectionState
import com.ilia.postupdateanalyzer.adb.model.PackageState
import com.ilia.postupdateanalyzer.desktop.browser.ExternalBrowser
import com.ilia.postupdateanalyzer.desktop.lifecycle.AppShutdown
import com.ilia.postupdateanalyzer.device.service.DeviceService
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotKind
import com.ilia.postupdateanalyzer.snapshot.repository.SnapshotRepository
import com.ilia.postupdateanalyzer.snapshot.service.SnapshotService
import com.ilia.postupdateanalyzer.support.IntegrationTest
import com.ilia.postupdateanalyzer.support.fake.FakeAdbEnvironment
import com.ilia.postupdateanalyzer.support.fake.FakeDevice
import org.assertj.core.api.Assertions.assertThat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.net.URI

@AutoConfigureMockMvc
class WebTest : IntegrationTest() {

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var devices: DeviceService

    @Autowired
    lateinit var snapshots: SnapshotService

    @Autowired
    lateinit var snapshotRepository: SnapshotRepository

    @Autowired
    lateinit var actionLog: ActionLogRepository

    @MockitoBean
    lateinit var shutdown: AppShutdown

    @MockitoBean
    lateinit var externalBrowser: ExternalBrowser

    /** A bare `<tr>` (action response) is dropped by HTML parsers unless it is inside a table. */
    private fun MvcResult.html(): Document {
        val body = response.contentAsString
        return Jsoup.parse(if (body.trimStart().startsWith("<tr")) "<table><tbody>$body</tbody></table>" else body)
    }

    private fun page(url: String, status: Int = 200): Document {
        val result = mvc.get(url).andReturn()
        assertThat(result.response.status).describedAs(result.response.contentAsString).isEqualTo(status)
        return result.html()
    }

    private fun htmxPost(url: String, vararg params: Pair<String, String>): MvcResult {
        return mvc.post(url) {
            header("HX-Request", "true")
            header("Origin", "http://localhost")
            params.forEach { (k, v) -> param(k, v) }
        }.andReturn()
    }

    private fun deviceId(): Long {
        return devices.scan().connected.first { it.record != null }.record!!.id
    }

    /** Two snapshots around a simulated vendor update. */
    private fun updatedPhone(): Triple<FakeDevice, Long, Pair<Long, Long>> {
        val phone = phone()
            .add("com.vendor.store", PackageState.UNINSTALLED, label = "Vendor Store")
            .add("com.vendor.cloud", PackageState.DISABLED, label = "Vendor Cloud")
        val id = deviceId()
        val before = snapshots.capture(id, 0, SnapshotKind.BEFORE_UPDATE, null)
        phone.buildFingerprint("google/panther/panther:14/UP1A/1:user/release-keys", "UP1A")
        phone.set("com.vendor.store", PackageState.ENABLED)
        phone.set("com.vendor.cloud", PackageState.ENABLED)
        phone.add("com.vendor.trash", label = "Trash <b>App</b>", system = false)
        val after = snapshots.capture(id, 0, SnapshotKind.AFTER_UPDATE, null)
        return Triple(phone, id, before.id to after.id)
    }

    /** One click on an action button of the comparison [before] → [after]. */
    private fun runAction(id: Long, before: Long, after: Long, pkg: String, action: String): MvcResult {
        return htmxPost("/devices/$id/actions", "package" to pkg, "action" to action, "before" to "$before", "after" to "$after")
    }

    private fun Document.row(packageName: String): Element {
        return selectFirst("tr[data-package=$packageName]")
            ?: error("no row for $packageName")
    }

    private fun Element.buttons(): List<String> {
        return select("button[name=action]").map { it.attr("value") }
    }

    @Nested
    inner class Home {
        @Test
        fun `explains how to install adb when it is missing`() {
            adbEnvironment.available = false

            val doc = page("/")

            assertThat(doc.getElementById("adb-missing")).isNotNull()
            assertThat(doc.text()).contains("Android SDK Platform-Tools")
        }

        @Test
        fun `explains how to connect a phone when none is connected`() {
            val doc = page("/")

            assertThat(doc.getElementById("no-devices")!!.text()).contains("USB debugging")
        }

        @Test
        fun `shows connected phones and hints for unauthorized ones`() {
            phone()
            adb.connect(FakeDevice("UNAUTH1")).state = DeviceConnectionState.UNAUTHORIZED

            val doc = page("/")

            val cards = doc.select(".device-card")
            assertThat(cards).hasSize(2)
            assertThat(doc.select(".device-card a.open-device")).hasSize(1)
            assertThat(doc.text()).contains("Google Pixel 7").contains("Allow USB debugging")
            assertThat(page("/fragments/devices").getElementById("devices")).isNotNull()
        }
    }

    @Nested
    inner class DevicePage {
        @Test
        fun `connected phone can take snapshots`() {
            phone()
            val doc = page("/devices/${deviceId()}")

            assertThat(doc.getElementById("take-snapshot")).isNotNull()
            assertThat(doc.select("input[name=kind][checked]").attr("value")).isEqualTo("BEFORE_UPDATE")
        }

        @Test
        fun `taking a snapshot returns the new list, a toast and suggests after-update next`() {
            phone()
            val id = deviceId()

            val result = htmxPost("/devices/$id/snapshots", "user" to "0", "kind" to "BEFORE_UPDATE", "note" to "<script>alert(1)</script>")

            assertThat(result.response.status).isEqualTo(200)
            val doc = result.html()
            assertThat(doc.select("#snapshot-table tbody tr")).hasSize(1)
            assertThat(doc.select("#toasts .toast").text()).contains("Saved \"Before update\" snapshot with 6 apps")
            assertThat(result.response.contentAsString).doesNotContain("<script>alert(1)</script>")
            assertThat(page("/devices/$id").select("input[name=kind][checked]").attr("value")).isEqualTo("AFTER_UPDATE")
        }

        @Test
        fun `errors come back as a toast without replacing the page`() {
            val phone = phone()
            val id = deviceId()
            phone.state = DeviceConnectionState.OFFLINE

            val result = htmxPost("/devices/$id/snapshots", "user" to "0", "kind" to "BEFORE_UPDATE")

            assertThat(result.response.status).isEqualTo(409)
            assertThat(result.response.getHeader("HX-Reswap")).isEqualTo("none")
            assertThat(result.html().select("#toasts .toast.error").text()).contains("not connected")
        }

        @Test
        fun `a disconnected phone shows its snapshots and a compare form with sensible defaults`() {
            val (phone, id, pair) = updatedPhone()
            adb.devices.remove(phone.serial)

            val doc = page("/devices/$id")

            assertThat(doc.getElementById("take-snapshot")).isNull()
            assertThat(doc.text()).contains("Connect this phone")
            assertThat(doc.select("select[name=before] option[selected]").attr("value")).isEqualTo(pair.first.toString())
            assertThat(doc.select("select[name=after] option[selected]").attr("value")).isEqualTo(pair.second.toString())
        }

        @Test
        fun `deleting a snapshot`() {
            val (_, id, pair) = updatedPhone()

            val result = mvc.delete("/snapshots/${pair.first}") { header("HX-Request", "true") }.andReturn()

            assertThat(result.response.status).isEqualTo(200)
            assertThat(result.html().select("#snapshot-table tbody tr")).hasSize(1)
            assertThat(snapshots.list(id)).hasSize(1)
        }

        @Test
        fun `a snapshot that actions were run from cannot be deleted`() {
            val (_, id, pair) = updatedPhone()
            runAction(id, pair.first, pair.second, "com.vendor.store", "UNINSTALL")
            val unused = snapshots.capture(id, 0, SnapshotKind.OTHER, null).id

            val doc = page("/devices/$id")

            val blocked = doc.select("tr[data-snapshot-id=${pair.first}] .delete-snapshot").single()
            assertThat(blocked.hasAttr("disabled")).isTrue()
            assertThat(blocked.hasAttr("hx-delete")).isFalse()
            assertThat(blocked.parent()!!.attr("title")).isEqualTo("Used by 1 action in History, so it cannot be deleted.")
            assertThat(doc.select("tr[data-snapshot-id=${pair.second}] .delete-snapshot").single().hasAttr("disabled")).isTrue()
            val free = doc.select("tr[data-snapshot-id=$unused] .delete-snapshot").single()
            assertThat(free.hasAttr("disabled")).isFalse()
            assertThat(free.attr("hx-delete")).isEqualTo("/snapshots/$unused")

            val result = mvc.delete("/snapshots/${pair.first}") { header("HX-Request", "true") }.andReturn()

            assertThat(result.response.status).isEqualTo(409)
            assertThat(result.response.getHeader("HX-Reswap")).isEqualTo("none")
            assertThat(result.html().select("#toasts .toast.error").text()).contains("has actions in History")
            assertThat(snapshots.list(id)).hasSize(3)
        }

        @Test
        fun `unknown device is a 404 page`() {
            assertThat(page("/devices/999", 404).getElementById("error-message")!!.text()).contains("not found")
        }
    }

    @Nested
    inner class ComparisonPage {
        @Test
        fun `shows changed apps with names and hides unchanged ones`() {
            val (_, _, pair) = updatedPhone()

            val doc = page("/compare?before=${pair.first}&after=${pair.second}")

            val visible = doc.select("tr.app-row:not([hidden])").map { it.attr("data-package") }
            assertThat(visible).containsExactlyInAnyOrder("com.vendor.store", "com.vendor.cloud", "com.vendor.trash")
            assertThat(doc.select("tr.app-row[hidden]")).hasSize(6)
            assertThat(doc.row("com.vendor.store").select(".change").text()).isEqualTo("Reinstalled")
            assertThat(doc.row("com.vendor.cloud").select(".change").text()).isEqualTo("Re-enabled")
            assertThat(doc.row("com.vendor.trash").select(".change").text()).isEqualTo("New app")
            assertThat(doc.row("com.vendor.store").select(".app-label").text()).isEqualTo("Vendor Store")
            assertThat(doc.row("com.vendor.store").select("img.app-icon").attr("src")).matches("/icons/[0-9a-f]{64}\\.png")
            assertThat(doc.select("#filters input[name=type]").map { it.attr("value") }).containsExactly("INSTALLED", "RESTORED", "RE_ENABLED")
            assertThat(doc.getElementById("show-unchanged")!!.hasAttr("checked")).isFalse()
            assertThat(doc.getElementById("same-build")).isNull()
        }

        @Test
        fun `app names from the phone are escaped`() {
            val (_, _, pair) = updatedPhone()

            val result = mvc.get("/compare?before=${pair.first}&after=${pair.second}").andReturn()

            assertThat(result.response.contentAsString).doesNotContain("Trash <b>App</b>").contains("Trash &lt;b&gt;App&lt;/b&gt;")
        }

        @Test
        fun `each row offers only the actions that fit its current state`() {
            val (phone, _, pair) = updatedPhone()
            phone.set("com.vendor.cloud", PackageState.DISABLED) // user already fixed this one
            phone.set("com.vendor.store", PackageState.UNINSTALLED)

            val doc = page("/compare?before=${pair.first}&after=${pair.second}")

            assertThat(doc.row("com.vendor.trash").buttons()).containsExactly("DISABLE", "UNINSTALL")
            assertThat(doc.row("com.vendor.cloud").buttons()).containsExactly("RESTORE", "UNINSTALL")
            assertThat(doc.row("com.vendor.store").buttons()).containsExactly("RESTORE", "DISABLE")
            assertThat(doc.row("com.vendor.trash").select(".user-app-hint").text()).contains("removes it completely")
            assertThat(doc.row("com.vendor.store").select(".current .state").text()).isEqualTo("Uninstalled")
            // one click = one action: no confirmation
            assertThat(doc.select("tr.app-row button[hx-confirm]")).isEmpty()
        }

        @Test
        fun `gone apps have no buttons`() {
            val (phone, _, pair) = updatedPhone()
            phone.remove("com.vendor.trash")

            val doc = page("/compare?before=${pair.first}&after=${pair.second}")

            val trash = doc.row("com.vendor.trash")
            assertThat(trash.buttons()).isEmpty()
            assertThat(trash.select(".gone-hint").text()).contains("reinstall it from the app store")
        }

        @Test
        fun `without the phone the state is unknown and there are no buttons`() {
            val (phone, _, pair) = updatedPhone()
            adb.devices.remove(phone.serial)

            val doc = page("/compare?before=${pair.first}&after=${pair.second}")

            assertThat(doc.getElementById("not-connected")).isNotNull()
            assertThat(doc.select("button[name=action]")).isEmpty()
        }

        @Test
        fun `warns when the build did not change`() {
            val phone = phone()
            val id = deviceId()
            val a = snapshots.capture(id, 0, SnapshotKind.BEFORE_UPDATE, null)
            phone.set("com.android.chrome", PackageState.DISABLED)
            val b = snapshots.capture(id, 0, SnapshotKind.AFTER_UPDATE, null)

            assertThat(page("/compare?before=${a.id}&after=${b.id}").getElementById("same-build")).isNotNull()
        }

        @Test
        fun `invalid comparisons show an error page`() {
            val (_, _, pair) = updatedPhone()

            assertThat(page("/compare?before=${pair.first}&after=${pair.first}", 400).text()).contains("two different snapshots")
            page("/compare?before=${pair.first}&after=abc", 400)
            page("/compare?before=${pair.first}", 400)
        }
    }

    @Nested
    inner class Actions {
        private fun act(id: Long, pair: Pair<Long, Long>, pkg: String, action: String): MvcResult {
            return runAction(id, pair.first, pair.second, pkg, action)
        }

        @Test
        fun `one click uninstalls one app and returns the updated row`() {
            val (phone, id, pair) = updatedPhone()

            val result = act(id, pair, "com.vendor.store", "UNINSTALL")

            assertThat(result.response.status).isEqualTo(200)
            val doc = result.html()
            assertThat(doc.row("com.vendor.store").select(".current .state").text()).isEqualTo("Uninstalled")
            assertThat(doc.row("com.vendor.store").buttons()).containsExactly("RESTORE", "DISABLE")
            assertThat(doc.select("#toasts .toast.success").text()).contains("Uninstall Vendor Store: Uninstall done.")
            assertThat(phone.state("com.vendor.store")).isEqualTo(PackageState.UNINSTALLED)
            assertThat(phone.state("com.vendor.cloud")).isEqualTo(PackageState.ENABLED)
            val entry = actionLog.find(id, null).single()
            assertThat(entry.status).isEqualTo(ActionStatus.SUCCESS)
            assertThat(entry.beforeSnapshotId).isEqualTo(pair.first)
        }

        @Test
        fun `uninstalling a user app leaves a row without buttons`() {
            val (phone, id, pair) = updatedPhone()

            val doc = act(id, pair, "com.vendor.trash", "UNINSTALL").html()

            assertThat(phone.state("com.vendor.trash")).isNull()
            assertThat(doc.row("com.vendor.trash").buttons()).isEmpty()
            assertThat(doc.row("com.vendor.trash").select(".current .state").text()).isEqualTo("Not on phone")
        }

        @Test
        fun `failures show an error toast`() {
            val (phone, id, pair) = updatedPhone()
            phone.failures["pm uninstall"] = "Failure [DELETE_FAILED_USER_RESTRICTED]"

            val doc = act(id, pair, "com.vendor.store", "UNINSTALL").html()

            assertThat(doc.select("#toasts .toast.error").text()).contains("Knox")
            assertThat(actionLog.find(id, ActionStatus.FAILED)).hasSize(1)
        }

        @Test
        fun `rejects packages and snapshots that do not belong together`() {
            val (_, id, pair) = updatedPhone()

            assertThat(act(id, pair, "com.not.in.comparison", "UNINSTALL").response.status).isEqualTo(404)
            assertThat(act(id + 1, pair, "com.vendor.store", "UNINSTALL").response.status).isEqualTo(400)
            assertThat(act(id, pair, "com.vendor.store", "EXPLODE").response.status).isEqualTo(400)
            assertThat(actionLog.find(null, null)).isEmpty()
        }

        @Test
        fun `actions cannot be triggered from other websites`() {
            val (phone, id, pair) = updatedPhone()

            val noHtmx = mvc.post("/devices/$id/actions") {
                param("package", "com.vendor.store"); param("action", "UNINSTALL")
                param("before", pair.first.toString()); param("after", pair.second.toString())
            }.andReturn()
            val crossOrigin = mvc.post("/devices/$id/actions") {
                header("HX-Request", "true"); header("Origin", "https://evil.example")
                param("package", "com.vendor.store"); param("action", "UNINSTALL")
                param("before", pair.first.toString()); param("after", pair.second.toString())
            }.andReturn()
            val rebinding = mvc.get("/") { header("Host", "evil.example:18765") }.andReturn()

            assertThat(noHtmx.response.status).isEqualTo(403)
            assertThat(crossOrigin.response.status).isEqualTo(403)
            assertThat(rebinding.response.status).isEqualTo(403)
            assertThat(phone.state("com.vendor.store")).isEqualTo(PackageState.ENABLED)
        }
    }

    @Nested
    inner class HistoryAndSettings {
        @Test
        fun `history groups actions by comparison, newest first, and filters by result`() {
            val (phone, id, pair) = updatedPhone()
            val (before, after) = pair
            val later = snapshots.capture(id, 0, SnapshotKind.OTHER, null).id
            runAction(id, before, after, "com.vendor.store", "UNINSTALL")
            phone.failures["pm disable-user"] = "Error: nope"
            runAction(id, after, later, "com.vendor.cloud", "DISABLE")
            runAction(id, before, after, "com.vendor.store", "RESTORE")

            val groups = page("/history").select(".history-group")
            assertThat(groups.map { it.id() }).containsExactly("compare-$before-$after", "compare-$after-$later")

            val first = groups[0]
            assertThat(first.select("header").text()).contains("Google Pixel 7").contains("user 0").contains("2 actions")
                .contains("Before update").contains("After update")
            assertThat(first.selectFirst("a.open-comparison")!!.attr("href")).isEqualTo("/compare?before=$before&after=$after")
            assertThat(first.select("tbody tr").map { it.attr("data-action") }).containsExactly("RESTORE", "UNINSTALL")
            assertThat(first.select("tbody tr").last()!!.select("pre").text()).contains("pm uninstall --user 0 com.vendor.store")
            assertThat(first.select("th").map { it.text() }).doesNotContain("Phone")

            val second = groups[1]
            assertThat(second.select("header").text()).contains("1 action").contains("After update").contains("Other")
            assertThat(second.select("tbody tr").map { it.attr("data-package") }).containsExactly("com.vendor.cloud")
            assertThat(second.select("tbody tr").text()).contains("Vendor Cloud").contains("Failed")

            val failed = page("/history?status=FAILED").select(".history-group")
            assertThat(failed.map { it.id() }).containsExactly("compare-$after-$later")
            assertThat(failed.select("tbody tr").map { it.attr("data-package") }).containsExactly("com.vendor.cloud")
            assertThat(page("/history?device=$id").select(".history-group")).hasSize(2)
            assertThat(page("/history?device=999").getElementById("history-empty")).isNotNull()
        }

        @Test
        fun `settings show adb and save a path`() {
            val doc = page("/settings")
            assertThat(doc.getElementById("adb-found")!!.text()).contains("/fake/platform-tools/adb")
            assertThat(doc.getElementById("data-dir")!!.text()).isEqualTo(dataDir.toAbsolutePath().toString())

            val bad = htmxPost("/settings/adb", "path" to "/nope/adb").html()
            assertThat(bad.getElementById("adb-not-found")).isNotNull()
            assertThat(bad.select("#toasts .toast.error").text()).contains("does not work")

            val good = htmxPost("/settings/adb", "path" to FakeAdbEnvironment.GOOD_PATH).html()
            assertThat(good.getElementById("adb-found")).isNotNull()
            assertThat(adbEnvironment.configuredPath).isEqualTo(FakeAdbEnvironment.GOOD_PATH)

            htmxPost("/settings/adb", "path" to "")
            assertThat(adbEnvironment.configuredPath).isNull()
        }

        @Test
        fun `quit stops the app`() {
            val result = htmxPost("/quit")

            assertThat(result.html().getElementById("goodbye")).isNotNull()
            verify(shutdown).quit()
        }
    }

    @Nested
    inner class Icons {
        @Test
        fun `icons are served with long caching`() {
            val (_, _, pair) = updatedPhone()
            val hash = snapshotRepository.packages(pair.second).first { it.iconHash != null }.iconHash!!

            val result = mvc.get("/icons/$hash.png").andReturn()

            assertThat(result.response.status).isEqualTo(200)
            assertThat(result.response.contentType).isEqualTo("image/png")
            assertThat(result.response.getHeader("Cache-Control")).contains("max-age=31536000").contains("immutable")
            assertThat(mvc.get("/icons/${"0".repeat(64)}.png").andReturn().response.status).isEqualTo(404)
            assertThat(mvc.get("/icons/not-a-hash.png").andReturn().response.status).isEqualTo(400)
        }
    }

    @Nested
    inner class ExternalLinks {
        private val platformTools = URI("https://developer.android.com/tools/releases/platform-tools")

        @Test
        fun `links from the app window open in the default browser`() {
            doReturn(true).`when`(externalBrowser).open(platformTools)

            val result = htmxPost("/open-link", "url" to platformTools.toString())

            assertThat(result.response.status).isEqualTo(204)
            verify(externalBrowser).open(platformTools)
        }

        @Test
        fun `reports when the default browser cannot be opened, so the page opens the link itself`() {
            assertThat(htmxPost("/open-link", "url" to platformTools.toString()).response.status).isEqualTo(500)
        }

        @Test
        fun `only https addresses are opened`() {
            listOf("http://example.com/", "file:///etc/passwd", "javascript:alert(1)", "https:///no-host", "not a url").forEach { url ->
                assertThat(htmxPost("/open-link", "url" to url).response.status).describedAs(url).isEqualTo(400)
            }
            assertThat(mvc.post("/open-link") { param("url", platformTools.toString()) }.andReturn().response.status).isEqualTo(403)
            verifyNoInteractions(externalBrowser)
        }
    }

    @Test
    fun `unknown pages and static assets`() {
        page("/no/such/page", 404)
        assertThat(mvc.get("/webjars/htmx.org/dist/htmx.min.js").andReturn().response.status).isEqualTo(200)
        assertThat(mvc.get("/webjars/picocss__pico/css/pico.min.css").andReturn().response.status).isEqualTo(200)
        assertThat(mvc.get("/js/app.js").andReturn().response.status).isEqualTo(200)
    }
}
