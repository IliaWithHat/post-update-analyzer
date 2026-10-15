package com.ilia.postupdateanalyzer.emulator

import com.ilia.postupdateanalyzer.action.model.ActionOutcome
import com.ilia.postupdateanalyzer.action.model.ActionRequest
import com.ilia.postupdateanalyzer.action.model.ActionStatus
import com.ilia.postupdateanalyzer.action.repository.ActionLogRepository
import com.ilia.postupdateanalyzer.action.service.PackageActionService
import com.ilia.postupdateanalyzer.adb.model.PackageAction
import com.ilia.postupdateanalyzer.adb.model.PackageAction.DISABLE
import com.ilia.postupdateanalyzer.adb.model.PackageAction.RESTORE
import com.ilia.postupdateanalyzer.adb.model.PackageAction.UNINSTALL
import com.ilia.postupdateanalyzer.compare.model.ChangeType
import com.ilia.postupdateanalyzer.compare.service.ComparisonService
import com.ilia.postupdateanalyzer.emulator.support.EmulatorTest
import com.ilia.postupdateanalyzer.emulator.support.SharedEmulator
import com.ilia.postupdateanalyzer.metadata.model.MetadataStatus
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotKind
import com.ilia.postupdateanalyzer.snapshot.repository.SnapshotRepository
import com.ilia.postupdateanalyzer.snapshot.service.SnapshotService
import com.ilia.postupdateanalyzer.support.TestSnapshots
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import javax.imageio.ImageIO

/** Every action against a real Android system, checked afterwards with plain adb. */
class EmulatorActionsTest : EmulatorTest() {

    @Autowired
    lateinit var actions: PackageActionService

    @Autowired
    lateinit var actionLog: ActionLogRepository

    @Autowired
    lateinit var snapshots: SnapshotService

    @Autowired
    lateinit var snapshotRepository: SnapshotRepository

    @Autowired
    lateinit var comparisons: ComparisonService

    /** The (before, after) snapshots every action of the test is run from. */
    private val comparison by lazy { TestSnapshots.emptyPair(snapshotRepository, emulatorDeviceId()) }

    private fun request(id: Long, pkg: String, action: PackageAction): ActionRequest {
        return ActionRequest(id, 0, pkg, action, comparison.first, comparison.second)
    }

    private fun act(action: PackageAction, pkg: String = EGG): ActionOutcome {
        return actions.apply(request(emulatorDeviceId(), pkg, action))
    }

    @Test
    fun `every state change works on a real phone`() {
        // E -> D -> E
        assertThat(act(DISABLE).entry.status).isEqualTo(ActionStatus.SUCCESS)
        assertThat(adb.state(EGG)).isEqualTo("DISABLED")
        assertThat(act(RESTORE).entry.status).isEqualTo(ActionStatus.SUCCESS)
        assertThat(adb.state(EGG)).isEqualTo("ENABLED")

        // E -> U -> E
        assertThat(act(UNINSTALL).entry.status).isEqualTo(ActionStatus.SUCCESS)
        assertThat(adb.state(EGG)).isEqualTo("UNINSTALLED")
        assertThat(act(RESTORE).entry.status).isEqualTo(ActionStatus.SUCCESS)
        assertThat(adb.state(EGG)).isEqualTo("ENABLED")

        // D -> U -> (restore brings it back disabled first, then enables it) E
        act(DISABLE)
        act(UNINSTALL)
        assertThat(adb.state(EGG)).isEqualTo("UNINSTALLED")
        val restore = act(RESTORE)
        assertThat(restore.entry.status).isEqualTo(ActionStatus.SUCCESS)
        assertThat(adb.state(EGG)).isEqualTo("ENABLED")

        // U -> D
        act(UNINSTALL)
        assertThat(act(DISABLE).entry.status).isEqualTo(ActionStatus.SUCCESS)
        assertThat(adb.state(EGG)).isEqualTo("DISABLED")

        // nothing to do
        assertThat(act(DISABLE).entry.status).isEqualTo(ActionStatus.SKIPPED)
        assertThat(actionLog.find(null, null)).hasSize(10)
    }

    @Test
    fun `user-installed apps are removed completely and cannot be restored`() {
        adb.install(SharedEmulator.testApk("test-trash-v1.apk"))
        assertThat(adb.state(TRASH_APP)).isEqualTo("ENABLED")

        val uninstall = act(UNINSTALL, TRASH_APP)
        assertThat(uninstall.entry.status).isEqualTo(ActionStatus.SUCCESS)
        assertThat(adb.state(TRASH_APP)).isNull()

        val restore = act(RESTORE, TRASH_APP)
        assertThat(restore.entry.status).isEqualTo(ActionStatus.FAILED)
        assertThat(restore.entry.message).contains("app store")
    }

    @Test
    fun `snapshots read real names, icons and versions, and the diff finds vendor changes`() {
        val id = emulatorDeviceId()
        adb.shell("pm uninstall --user 0 $EGG")
        val before = snapshots.capture(id, 0, SnapshotKind.BEFORE_UPDATE, null)

        assertThat(before.metadataStatus).isEqualTo(MetadataStatus.FULL)
        assertThat(before.build.sdkInt).isEqualTo(33)
        assertThat(before.packageCount).isGreaterThan(100)
        val packages = snapshotRepository.packages(before.id).associateBy { it.packageName }
        assertThat(packages.getValue("com.android.settings").label).isEqualTo("Settings")
        assertThat(packages.getValue(EGG).state.name).isEqualTo("UNINSTALLED")
        assertThat(packages.getValue(EGG).label).isNotBlank() // name of an uninstalled system app is known too
        val icon = ImageIO.read(snapshotRepository.icon(packages.getValue("com.android.settings").iconHash!!)!!.inputStream())
        assertThat(icon.width).isEqualTo(96)

        // the "vendor update"
        adb.shell("cmd package install-existing --user 0 $EGG")
        adb.shell("pm disable-user --user 0 $DAYDREAMS")
        adb.install(SharedEmulator.testApk("test-trash-v1.apk"))
        val after = snapshots.capture(id, 0, SnapshotKind.AFTER_UPDATE, null)

        val comparison = comparisons.compare(before.id, after.id)
        assertThat(comparison.diff(EGG)!!.change).isEqualTo(ChangeType.RESTORED)
        assertThat(comparison.diff(DAYDREAMS)!!.change).isEqualTo(ChangeType.DISABLED)
        val trash = comparison.diff(TRASH_APP)!!
        assertThat(trash.change).isEqualTo(ChangeType.INSTALLED)
        assertThat(trash.label).isEqualTo("Free Games Trash")
        assertThat(trash.system).isFalse()
        assertThat(comparison.changedCount).isEqualTo(3)

        // the helper left nothing behind
        assertThat(adb.shell("ls /data/local/tmp")).doesNotContain("pua-helper")
    }

    @Test
    fun `an app update is a version change, not a state change`() {
        val id = emulatorDeviceId()
        adb.install(SharedEmulator.testApk("test-trash-v1.apk"))
        val before = snapshots.capture(id, 0, SnapshotKind.BEFORE_UPDATE, null)
        adb.install(SharedEmulator.testApk("test-trash-v2.apk"))
        val after = snapshots.capture(id, 0, SnapshotKind.AFTER_UPDATE, null)

        val diff = comparisons.compare(before.id, after.id).diff(TRASH_APP)!!

        assertThat(diff.change).isEqualTo(ChangeType.UNCHANGED)
        assertThat(diff.versionChange.name).isEqualTo("UPGRADED")
    }

    @Test
    fun `a phone that disconnected gives a clear failure and works again after reconnecting`() {
        // Built while the emulator is still connected: finding its device id needs adb.
        val disable = request(emulatorDeviceId(), EGG, DISABLE)
        adb.disconnect()
        try {
            val outcome = actions.apply(disable)
            assertThat(outcome.entry.status).isEqualTo(ActionStatus.FAILED)
            assertThat(outcome.entry.message).contains("not connected")
        } finally {
            adb.connect()
        }
        assertThat(actions.apply(disable).entry.status).isEqualTo(ActionStatus.SUCCESS)
    }
}
