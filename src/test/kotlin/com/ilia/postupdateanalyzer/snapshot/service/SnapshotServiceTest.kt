package com.ilia.postupdateanalyzer.snapshot.service

import com.ilia.postupdateanalyzer.action.model.ActionLogEntry
import com.ilia.postupdateanalyzer.action.model.ActionStatus
import com.ilia.postupdateanalyzer.action.repository.ActionLogRepository
import com.ilia.postupdateanalyzer.adb.exception.AdbCommandException
import com.ilia.postupdateanalyzer.adb.model.AdbResult
import com.ilia.postupdateanalyzer.adb.model.AndroidUser
import com.ilia.postupdateanalyzer.adb.model.DeviceConnectionState
import com.ilia.postupdateanalyzer.adb.model.PackageAction
import com.ilia.postupdateanalyzer.adb.model.PackageState
import com.ilia.postupdateanalyzer.device.exception.DeviceBusyException
import com.ilia.postupdateanalyzer.device.exception.DeviceNotConnectedException
import com.ilia.postupdateanalyzer.device.exception.NotFoundException
import com.ilia.postupdateanalyzer.device.service.DeviceService
import com.ilia.postupdateanalyzer.metadata.model.MetadataStatus
import com.ilia.postupdateanalyzer.snapshot.exception.EmptySnapshotException
import com.ilia.postupdateanalyzer.snapshot.model.BuildInfo
import com.ilia.postupdateanalyzer.snapshot.model.NewSnapshot
import com.ilia.postupdateanalyzer.snapshot.model.Snapshot
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotKind
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotPackage
import com.ilia.postupdateanalyzer.snapshot.repository.SnapshotRepository
import com.ilia.postupdateanalyzer.support.IntegrationTest
import com.ilia.postupdateanalyzer.support.TestSnapshots
import com.ilia.postupdateanalyzer.support.fake.TestPngs
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class SnapshotServiceTest : IntegrationTest() {

    @Autowired
    lateinit var snapshots: SnapshotService

    @Autowired
    lateinit var repository: SnapshotRepository

    @Autowired
    lateinit var devices: DeviceService

    @Autowired
    lateinit var actionLog: ActionLogRepository

    private fun deviceId(): Long {
        return devices.scan().connected.single().record!!.id
    }

    private fun iconCount(): Int {
        return jdbc.sql("SELECT count(*) FROM app_icon").query(Int::class.java).single()
    }

    @Test
    fun `stores state, names, icons, versions and build of every app`() {
        val phone = phone()
            .add("com.vendor.disabled", PackageState.DISABLED, label = "Vendor Store")
            .add("com.vendor.removed", PackageState.UNINSTALLED, label = "Vendor Browser", versionCode = 42)

        val snapshot = snapshots.capture(deviceId(), 0, SnapshotKind.BEFORE_UPDATE, "  before One UI 7  ")

        assertThat(snapshot.kind).isEqualTo(SnapshotKind.BEFORE_UPDATE)
        assertThat(snapshot.note).isEqualTo("before One UI 7")
        assertThat(snapshot.packageCount).isEqualTo(8)
        assertThat(snapshot.metadataStatus).isEqualTo(MetadataStatus.FULL)
        assertThat(snapshot.build.fingerprint).isEqualTo(phone.props["ro.build.fingerprint"])
        assertThat(snapshot.build.sdkInt).isEqualTo(33)
        assertThat(snapshot.adbSerial).isEqualTo(phone.serial)

        val packages = repository.packages(snapshot.id).associateBy { it.packageName }
        assertThat(packages.getValue("com.android.settings").state).isEqualTo(PackageState.ENABLED)
        assertThat(packages.getValue("com.android.settings").label).isEqualTo("Settings")
        assertThat(packages.getValue("com.vendor.disabled").state).isEqualTo(PackageState.DISABLED)
        assertThat(packages.getValue("com.vendor.removed").state).isEqualTo(PackageState.UNINSTALLED)
        assertThat(packages.getValue("com.vendor.removed").versionCode).isEqualTo(42)
        assertThat(packages.getValue("com.example.game").system).isFalse()
        assertThat(packages.getValue("com.android.chrome").system).isTrue()
        val iconHash = packages.getValue("com.android.chrome").iconHash!!
        assertThat(repository.icon(iconHash)).startsWith(0x89.toByte())
    }

    @Test
    fun `the helper is removed from the phone afterwards`() {
        val phone = phone()

        snapshots.capture(deviceId(), 0, SnapshotKind.OTHER, null)

        assertThat(phone.pushedFiles).isEmpty()
        assertThat(adb.shellCommands(phone.serial)).anyMatch { it.startsWith("CLASSPATH=/data/local/tmp/pua-helper-") }
        assertThat(adb.shellCommands(phone.serial).last()).startsWith("rm -f /data/local/tmp/pua-helper-")
    }

    @Test
    fun `still saves the snapshot with package names when the helper is blocked`() {
        val phone = phone()
        phone.helperWorks = false

        val snapshot = snapshots.capture(deviceId(), 0, SnapshotKind.BEFORE_UPDATE, null)

        assertThat(snapshot.metadataStatus).isEqualTo(MetadataStatus.UNAVAILABLE)
        assertThat(snapshot.metadataMessage).contains("blocked by ROM")
        assertThat(repository.packages(snapshot.id)).hasSize(6).allMatch { it.label == null && it.iconHash == null }
        assertThat(phone.pushedFiles).isEmpty()
    }

    @Test
    fun `reports partial metadata when the helper output is cut off`() {
        val phone = phone()
        // the normal output without the end marker and the last app
        phone.helperOutputOverride = phone.helperOutput().lines().dropLast(2).joinToString("\n")

        val snapshot = snapshots.capture(deviceId(), 0, SnapshotKind.OTHER, null)

        assertThat(snapshot.metadataStatus).isEqualTo(MetadataStatus.PARTIAL)
        assertThat(snapshot.metadataMessage).contains("cut off")
        assertThat(repository.packages(snapshot.id).count { it.label != null }).isEqualTo(5)
    }

    @Test
    fun `refuses to save when the phone reports no apps`() {
        val phone = phone()
        phone.packages.keys.toList().forEach(phone::remove)
        val id = deviceId()

        assertThatThrownBy { snapshots.capture(id, 0, SnapshotKind.AFTER_UPDATE, null) }
            .isInstanceOf(EmptySnapshotException::class.java)
            .hasMessageContaining("Nothing was saved")
        assertThat(snapshots.list(id)).isEmpty()
    }

    @Test
    fun `nothing is saved when listing packages fails`() {
        val phone = phone()
        val id = deviceId()
        adb.interceptors += { args -> if (args.getOrNull(3)?.startsWith("pm list packages -d") == true) AdbResult(255, "", "Exception occurred while executing 'list'") else null }

        assertThatThrownBy { snapshots.capture(id, 0, SnapshotKind.BEFORE_UPDATE, null) }.isInstanceOf(AdbCommandException::class.java)
        assertThat(snapshots.list(id)).isEmpty()
        assertThat(phone.pushedFiles).isEmpty()
    }

    @Test
    fun `fails clearly when the phone is not connected`() {
        val phone = phone()
        val id = deviceId()
        phone.state = DeviceConnectionState.OFFLINE

        assertThatThrownBy { snapshots.capture(id, 0, SnapshotKind.BEFORE_UPDATE, null) }
            .isInstanceOf(DeviceNotConnectedException::class.java)
            .hasMessageContaining("not connected")
    }

    @Test
    fun `unknown device`() {
        assertThatThrownBy { snapshots.capture(999, 0, SnapshotKind.BEFORE_UPDATE, null) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `snapshots of another Android user use the user flag`() {
        val phone = phone()
        phone.users += AndroidUser(10, "Work profile", running = true)
        phone.set("com.android.chrome", PackageState.UNINSTALLED, user = 10)

        val snapshot = snapshots.capture(deviceId(), 10, SnapshotKind.BEFORE_UPDATE, null)

        assertThat(snapshot.androidUserId).isEqualTo(10)
        assertThat(adb.shellCommands(phone.serial)).contains("pm list packages -u --show-versioncode --user 10")
        assertThat(repository.packages(snapshot.id).single { it.packageName == "com.android.chrome" }.state).isEqualTo(PackageState.UNINSTALLED)
    }

    @Test
    fun `older Android without version codes in pm list still works`() {
        val phone = phone()
        phone.sdk = 26
        phone.props["ro.build.version.sdk"] = "26"

        val snapshot = snapshots.capture(deviceId(), 0, SnapshotKind.BEFORE_UPDATE, null)

        assertThat(adb.shellCommands(phone.serial)).contains("pm list packages -u --user 0").doesNotContain("pm list packages -u --show-versioncode --user 0")
        // versions come from the helper instead
        assertThat(repository.packages(snapshot.id)).allMatch { it.versionCode == 1L }
    }

    @Test
    fun `a second snapshot of the same phone at the same time is refused`() {
        val phone = phone()
        val id = deviceId()
        val inHelper = CountDownLatch(1)
        val release = CountDownLatch(1)
        adb.interceptors += { args ->
            if (args.getOrNull(3)?.startsWith("CLASSPATH=") == true && inHelper.count > 0) {
                inHelper.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            null
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first: Future<Snapshot> = pool.submit<Snapshot> { snapshots.capture(id, 0, SnapshotKind.BEFORE_UPDATE, null) }
            assertThat(inHelper.await(10, TimeUnit.SECONDS)).isTrue()

            assertThatThrownBy { snapshots.capture(id, 0, SnapshotKind.BEFORE_UPDATE, null) }.isInstanceOf(DeviceBusyException::class.java)

            release.countDown()
            assertThat(first.get(10, TimeUnit.SECONDS).packageCount).isEqualTo(phone.packages.size)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `identical icons are stored once`() {
        phone()
        val id = deviceId()

        snapshots.capture(id, 0, SnapshotKind.BEFORE_UPDATE, null)
        val iconsAfterFirst = iconCount()
        snapshots.capture(id, 0, SnapshotKind.AFTER_UPDATE, null)

        assertThat(iconsAfterFirst).isEqualTo(6)
        assertThat(iconCount()).isEqualTo(6)
    }

    @Test
    fun `deleting a snapshot removes its packages and unused icons only`() {
        val phone = phone()
        val id = deviceId()
        val first = snapshots.capture(id, 0, SnapshotKind.BEFORE_UPDATE, null)
        phone.packages.getValue("com.android.chrome").icon = TestPngs.png("new chrome icon")
        val second = snapshots.capture(id, 0, SnapshotKind.AFTER_UPDATE, null)
        assertThat(iconCount()).isEqualTo(7)

        snapshots.delete(first.id)

        assertThat(repository.packages(first.id)).isEmpty()
        assertThat(repository.packages(second.id)).hasSize(6)
        assertThat(iconCount()).isEqualTo(6)
        assertThatThrownBy { snapshots.delete(first.id) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `the database itself keeps snapshots that actions were run from`() {
        phone()
        val id = deviceId()
        val (before, after) = TestSnapshots.emptyPair(repository, id)
        actionLog.insert(
            ActionLogEntry(
                createdAt = clock.instant(), deviceId = id, androidUserId = 0, packageName = "com.vendor.bloat", label = null,
                action = PackageAction.DISABLE, beforeSnapshotId = before, afterSnapshotId = after, stateBefore = null,
                stateAfter = null, status = ActionStatus.SKIPPED, commands = null, output = null, message = null, warning = null,
                durationMs = 0,
            ),
        )

        assertThatThrownBy { repository.delete(before) }.isInstanceOf(DataAccessException::class.java).hasMessageContaining("FOREIGN KEY")
        assertThatThrownBy { repository.delete(after) }.isInstanceOf(DataAccessException::class.java).hasMessageContaining("FOREIGN KEY")
        assertThat(repository.findById(before)).isNotNull()
        assertThat(repository.findById(after)).isNotNull()
    }

    @Test
    fun `a failing insert leaves nothing behind`() {
        phone()
        val id = deviceId()
        val duplicate = SnapshotPackage("com.a.b", PackageState.ENABLED, true, null, 1, "1", "A", null, null, null, "deadbeef")

        assertThatThrownBy {
            repository.insert(
                NewSnapshot(
                    id, 0, SnapshotKind.OTHER, null, clock.instant(), "serial",
                    BuildInfo(null, null, null, null, null, null), MetadataStatus.FULL, null,
                    packages = listOf(duplicate, duplicate),
                    icons = mapOf("deadbeef" to byteArrayOf(1)),
                ),
            )
        }.isInstanceOf(Exception::class.java)

        assertThat(snapshots.list(id)).isEmpty()
        assertThat(iconCount()).isZero()
    }
}
