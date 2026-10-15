package com.ilia.postupdateanalyzer.action.service

import com.ilia.postupdateanalyzer.action.model.ActionOutcome
import com.ilia.postupdateanalyzer.action.model.ActionRequest
import com.ilia.postupdateanalyzer.action.model.ActionStatus
import com.ilia.postupdateanalyzer.action.repository.ActionLogRepository
import com.ilia.postupdateanalyzer.adb.model.AdbResult
import com.ilia.postupdateanalyzer.adb.model.AndroidUser
import com.ilia.postupdateanalyzer.adb.model.DeviceConnectionState
import com.ilia.postupdateanalyzer.adb.model.PackageAction
import com.ilia.postupdateanalyzer.adb.model.PackageAction.DISABLE
import com.ilia.postupdateanalyzer.adb.model.PackageAction.RESTORE
import com.ilia.postupdateanalyzer.adb.model.PackageAction.UNINSTALL
import com.ilia.postupdateanalyzer.adb.model.PackageState
import com.ilia.postupdateanalyzer.adb.model.PackageState.DISABLED
import com.ilia.postupdateanalyzer.adb.model.PackageState.ENABLED
import com.ilia.postupdateanalyzer.adb.model.PackageState.UNINSTALLED
import com.ilia.postupdateanalyzer.device.service.DeviceService
import com.ilia.postupdateanalyzer.snapshot.exception.SnapshotInUseException
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotKind
import com.ilia.postupdateanalyzer.snapshot.repository.SnapshotRepository
import com.ilia.postupdateanalyzer.snapshot.service.SnapshotService
import com.ilia.postupdateanalyzer.support.IntegrationTest
import com.ilia.postupdateanalyzer.support.TestSnapshots
import com.ilia.postupdateanalyzer.support.fake.FakeDevice
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired

class PackageActionServiceTest : IntegrationTest() {

    @Autowired
    lateinit var actions: PackageActionService

    @Autowired
    lateinit var actionLog: ActionLogRepository

    @Autowired
    lateinit var devices: DeviceService

    @Autowired
    lateinit var snapshots: SnapshotService

    @Autowired
    lateinit var snapshotRepository: SnapshotRepository

    private val pkg = "com.vendor.bloat"

    /** Phone id -> the (before, after) snapshots every action of the test is run from. */
    private val comparisons = mutableMapOf<Long, Pair<Long, Long>>()

    private fun setup(state: PackageState? = ENABLED, system: Boolean = true): Pair<FakeDevice, Long> {
        val phone = phone()
        if (state != null) phone.add(pkg, state, system = system, label = "Vendor Bloat")
        val id = devices.scan().connected.single().record!!.id
        return phone to id
    }

    private fun apply(id: Long, action: PackageAction, packageName: String = pkg): ActionOutcome {
        val (before, after) = comparisons.getOrPut(id) { TestSnapshots.emptyPair(snapshotRepository, id) }
        return actions.apply(ActionRequest(id, 0, packageName, action, before, after))
    }

    @ParameterizedTest(name = "{0} on {1} -> {2}")
    @MethodSource("transitions")
    fun `every action from every state ends in the wanted state and is logged`(action: PackageAction, from: PackageState, expected: PackageState) {
        val (phone, id) = setup(from)

        val outcome = apply(id, action)

        assertThat(phone.state(pkg)).isEqualTo(expected)
        assertThat(outcome.current?.state).isEqualTo(expected)
        val entry = actionLog.find(id, null).single()
        assertThat(entry.action).isEqualTo(action)
        assertThat(entry.stateBefore).isEqualTo(from)
        assertThat(entry.stateAfter).isEqualTo(expected)
        assertThat(entry.label).isNull() // no snapshot yet, so the name is unknown
        if (from == expected) {
            assertThat(entry.status).isEqualTo(ActionStatus.SKIPPED)
            assertThat(entry.commands).isNull()
        } else {
            assertThat(entry.status).isEqualTo(ActionStatus.SUCCESS)
            assertThat(entry.commands).isNotBlank()
            assertThat(entry.output).contains("$ ")
        }
    }

    @Test
    fun `restoring an app that was disabled before it was uninstalled needs two steps`() {
        val (phone, id) = setup(UNINSTALLED)
        // like on a real phone: disable-user on an uninstalled app is remembered and comes back with it
        phone.shell("pm disable-user --user 0 $pkg")

        val outcome = apply(id, RESTORE)

        assertThat(outcome.entry.status).isEqualTo(ActionStatus.SUCCESS)
        assertThat(phone.state(pkg)).isEqualTo(ENABLED)
        assertThat(outcome.entry.commands).isEqualTo("cmd package install-existing --user 0 $pkg\npm enable --user 0 $pkg")
    }

    @Test
    fun `uninstalling a user app removes it completely and it cannot be restored`() {
        val (phone, id) = setup(ENABLED, system = false)

        val uninstall = apply(id, UNINSTALL)
        assertThat(uninstall.entry.status).isEqualTo(ActionStatus.SUCCESS)
        assertThat(uninstall.current).isNull()
        assertThat(uninstall.entry.stateAfter).isNull()
        assertThat(phone.state(pkg)).isNull()

        val restore = apply(id, RESTORE)
        assertThat(restore.entry.status).isEqualTo(ActionStatus.FAILED)
        assertThat(restore.entry.message).contains("Reinstall it from the app store").contains(pkg)
        assertThat(adb.shellCommands(phone.serial)).noneMatch { it.startsWith("cmd package install-existing") }
    }

    @Test
    fun `uninstalling something that is not on the phone is skipped, disabling it fails`() {
        val (_, id) = setup(state = null)

        assertThat(apply(id, UNINSTALL).entry.status).isEqualTo(ActionStatus.SKIPPED)
        val disable = apply(id, DISABLE)
        assertThat(disable.entry.status).isEqualTo(ActionStatus.FAILED)
        assertThat(disable.entry.message).contains("not on the phone")
    }

    @Test
    fun `Knox restriction is explained and logged as failed`() {
        val (phone, id) = setup()
        phone.failures["pm uninstall"] = "Failure [DELETE_FAILED_USER_RESTRICTED]"

        val outcome = apply(id, UNINSTALL)

        assertThat(outcome.entry.status).isEqualTo(ActionStatus.FAILED)
        assertThat(outcome.entry.message).contains("Knox").contains("disabling")
        assertThat(outcome.entry.stateAfter).isEqualTo(ENABLED)
        assertThat(phone.state(pkg)).isEqualTo(ENABLED)
    }

    @Test
    fun `a command that reports success but changes nothing is a failure`() {
        val (phone, id) = setup()
        phone.ignored += "pm disable-user"

        val outcome = apply(id, DISABLE)

        assertThat(outcome.entry.status).isEqualTo(ActionStatus.FAILED)
        assertThat(outcome.entry.message).contains("still reports the app as enabled")
        // it does not loop: the same command was sent once
        assertThat(adb.shellCommands(phone.serial).count { it.startsWith("pm disable-user") }).isEqualTo(1)
    }

    @Test
    fun `a failing force-stop does not fail the disable`() {
        val (phone, id) = setup()
        phone.failures["am force-stop"] = "Error: something"

        assertThat(apply(id, DISABLE).entry.status).isEqualTo(ActionStatus.SUCCESS)
        assertThat(phone.state(pkg)).isEqualTo(DISABLED)
    }

    @Test
    fun `a disconnected phone gives a failed log entry`() {
        val (phone, id) = setup()
        phone.state = DeviceConnectionState.OFFLINE

        val outcome = apply(id, UNINSTALL)

        assertThat(outcome.entry.status).isEqualTo(ActionStatus.FAILED)
        assertThat(outcome.entry.message).contains("not connected")
        assertThat(outcome.current).isNull()
    }

    @Test
    fun `the phone disappearing in the middle of an action is a failed log entry`() {
        val (phone, id) = setup()
        adb.interceptors += { args ->
            if (args.getOrNull(3)?.startsWith("pm uninstall") == true) AdbResult(1, "", "error: device offline") else null
        }

        val outcome = apply(id, UNINSTALL)

        assertThat(outcome.entry.status).isEqualTo(ActionStatus.FAILED)
        assertThat(outcome.entry.message).contains("not available")
        assertThat(phone.state(pkg)).isEqualTo(ENABLED)
    }

    @Test
    fun `a command timeout is a failed log entry`() {
        val (_, id) = setup()
        adb.interceptors += { args ->
            if (args.getOrNull(3)?.startsWith("pm uninstall") == true) AdbResult(-1, "", "", timedOut = true) else null
        }

        val outcome = apply(id, UNINSTALL)

        assertThat(outcome.entry.status).isEqualTo(ActionStatus.FAILED)
        assertThat(outcome.entry.message).contains("did not answer in time")
    }

    @Test
    fun `disabling on Android 5 is not possible over adb`() {
        adb.connect(FakeDevice("OLD2", sdk = 22).add(pkg))
        val id = devices.scan().connected.single().record!!.id

        val outcome = apply(id, DISABLE)

        assertThat(outcome.entry.status).isEqualTo(ActionStatus.FAILED)
        assertThat(outcome.entry.message).contains("Android 6.0")
    }

    @Test
    fun `Android 5 uses pm hide and pm unhide`() {
        val newPhone = adb.connect(FakeDevice("OLD1", sdk = 22).add(pkg))
        val id = devices.scan().connected.single { it.adb.serial == "OLD1" }.record!!.id

        assertThat(apply(id, UNINSTALL).entry.commands).isEqualTo("pm hide --user 0 $pkg")
        assertThat(apply(id, RESTORE).entry.commands).isEqualTo("pm unhide --user 0 $pkg")
        assertThat(newPhone.state(pkg)).isEqualTo(ENABLED)
    }

    @Test
    fun `warns when the phone also changed the app for another user`() {
        val (phone, id) = setup()
        phone.users += AndroidUser(10, "Dual apps", running = true)
        phone.set(pkg, ENABLED, user = 10)
        // An OEM ROM that uninstalls the app for all users
        adb.interceptors += { args ->
            if (args.getOrNull(3) == "pm uninstall --user 0 $pkg") {
                phone.set(pkg, UNINSTALLED, user = 10)
                null
            } else {
                null
            }
        }

        val outcome = apply(id, UNINSTALL)

        assertThat(outcome.entry.status).isEqualTo(ActionStatus.SUCCESS)
        assertThat(outcome.entry.warning).contains("user 10: Enabled → Uninstalled")
    }

    @Test
    fun `log entries remember the app name and the comparison, whose snapshots then cannot be deleted`() {
        val (_, id) = setup()
        val before = snapshots.capture(id, 0, SnapshotKind.BEFORE_UPDATE, null)
        val after = snapshots.capture(id, 0, SnapshotKind.AFTER_UPDATE, null)

        val outcome = actions.apply(ActionRequest(id, 0, pkg, DISABLE, beforeSnapshotId = before.id, afterSnapshotId = after.id))

        assertThat(outcome.entry.label).isEqualTo("Vendor Bloat")
        assertThat(outcome.entry.beforeSnapshotId).isEqualTo(before.id)
        assertThat(outcome.entry.afterSnapshotId).isEqualTo(after.id)
        assertThat(outcome.entry.durationMs).isGreaterThanOrEqualTo(0)

        assertThatThrownBy { snapshots.delete(before.id) }.isInstanceOf(SnapshotInUseException::class.java)
        assertThatThrownBy { snapshots.delete(after.id) }.isInstanceOf(SnapshotInUseException::class.java)
        assertThat(snapshots.list(id)).hasSize(2)
        assertThat(actionLog.find(id, null).single().beforeSnapshotId).isEqualTo(before.id)
    }

    @Test
    fun `invalid package names never reach the phone`() {
        val (phone, id) = setup()

        assertThatThrownBy { apply(id, UNINSTALL, packageName = "com.x;reboot") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(adb.shellCommands(phone.serial)).noneMatch { it.contains("reboot") }
    }

    @Test
    fun `history can be filtered by status`() {
        val (phone, id) = setup()
        apply(id, DISABLE)
        apply(id, DISABLE)
        phone.failures["pm uninstall"] = "Failure [DELETE_FAILED_INTERNAL_ERROR]"
        apply(id, UNINSTALL)

        assertThat(actionLog.find(id, ActionStatus.SUCCESS)).hasSize(1)
        assertThat(actionLog.find(id, ActionStatus.SKIPPED)).hasSize(1)
        assertThat(actionLog.find(id, ActionStatus.FAILED)).hasSize(1)
        assertThat(actionLog.find(null, null).map { it.status })
            .containsExactly(ActionStatus.FAILED, ActionStatus.SKIPPED, ActionStatus.SUCCESS)
    }

    companion object {
        @JvmStatic
        fun transitions(): List<Arguments> {
            return listOf(
                Arguments.of(UNINSTALL, ENABLED, UNINSTALLED),
                Arguments.of(UNINSTALL, DISABLED, UNINSTALLED),
                Arguments.of(UNINSTALL, UNINSTALLED, UNINSTALLED),
                Arguments.of(DISABLE, ENABLED, DISABLED),
                Arguments.of(DISABLE, DISABLED, DISABLED),
                Arguments.of(DISABLE, UNINSTALLED, DISABLED),
                Arguments.of(RESTORE, ENABLED, ENABLED),
                Arguments.of(RESTORE, DISABLED, ENABLED),
                Arguments.of(RESTORE, UNINSTALLED, ENABLED),
            )
        }
    }
}
