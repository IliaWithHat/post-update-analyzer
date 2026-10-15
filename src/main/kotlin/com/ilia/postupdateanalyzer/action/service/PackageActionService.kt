package com.ilia.postupdateanalyzer.action.service

import com.ilia.postupdateanalyzer.action.model.ActionLogEntry
import com.ilia.postupdateanalyzer.action.model.ActionOutcome
import com.ilia.postupdateanalyzer.action.model.ActionRequest
import com.ilia.postupdateanalyzer.action.model.ActionStatus
import com.ilia.postupdateanalyzer.action.repository.ActionLogRepository
import com.ilia.postupdateanalyzer.adb.client.AdbClient
import com.ilia.postupdateanalyzer.adb.command.PackageCommands
import com.ilia.postupdateanalyzer.adb.command.PackageNames
import com.ilia.postupdateanalyzer.adb.exception.AdbException
import com.ilia.postupdateanalyzer.adb.exception.UnsupportedActionException
import com.ilia.postupdateanalyzer.adb.model.PackageAction
import com.ilia.postupdateanalyzer.adb.model.PackageState
import com.ilia.postupdateanalyzer.adb.parser.AdbErrors
import com.ilia.postupdateanalyzer.device.model.LiveDevice
import com.ilia.postupdateanalyzer.device.model.LivePackage
import com.ilia.postupdateanalyzer.device.service.DeviceLocks
import com.ilia.postupdateanalyzer.device.service.DeviceService
import com.ilia.postupdateanalyzer.device.service.PackageStateReader
import com.ilia.postupdateanalyzer.snapshot.repository.SnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

/**
 * Runs exactly one action on one package, verifies the result on the phone and writes it to the action log.
 */
@Service
class PackageActionService(
    private val adb: AdbClient,
    private val devices: DeviceService,
    private val locks: DeviceLocks,
    private val stateReader: PackageStateReader,
    private val snapshots: SnapshotRepository,
    private val actionLog: ActionLogRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun apply(request: ActionRequest): ActionOutcome {
        PackageNames.requireValid(request.packageName)
        val device = devices.get(request.deviceId)
        val started = System.nanoTime()
        val recorder = Recorder(request, snapshots.latestAppInfo(device.id, request.packageName)?.label, started)

        val live = devices.liveOrNull(device)
            ?: return ActionOutcome(recorder.finish(ActionStatus.FAILED, "${device.displayName} is not connected."), null)

        return locks.withLock(device, Duration.ofMinutes(2), "The phone is busy with another snapshot or action. Try again shortly.") {
            run(request, live, recorder)
        }
    }

    private fun run(request: ActionRequest, live: LiveDevice, recorder: Recorder): ActionOutcome {
        val sdk = live.props.sdk
        val user = request.userId
        val pkg = request.packageName
        val action = request.action

        val before = try {
            stateReader.readOne(live.adbSerial, user, sdk, pkg)
        } catch (e: AdbException) {
            return ActionOutcome(recorder.finish(ActionStatus.FAILED, e.message), null)
        }
        recorder.stateBefore = before?.state
        recorder.stateAfter = before?.state

        if (achieved(action, before)) {
            val message = if (before == null) "The app is not on the phone." else "The app is already ${before.state.title.lowercase()}."
            return ActionOutcome(recorder.finish(ActionStatus.SKIPPED, message), before)
        }

        val otherUsersBefore = otherUserStates(live, user, pkg)
        var state = before
        return try {
            if (before == null) {
                // Only apps whose APK is still on the phone can be brought back. A user-installed app that was
                // uninstalled is gone; the user reinstalls it from the app store using the package name.
                throw ActionFailedException(
                    "The app is not on the phone anymore. Reinstall it from the app store (package $pkg).",
                )
            }
            for (attempt in 1..3) {
                if (achieved(action, state)) break
                val previous = state
                step(live, user, pkg, state, action, recorder)
                state = stateReader.readOne(live.adbSerial, user, sdk, pkg)
                recorder.stateAfter = state?.state
                if (state?.state == previous?.state) break
            }
            if (!achieved(action, state)) {
                val now = state?.state?.title?.lowercase() ?: "not on the phone"
                throw ActionFailedException(recorder.lastFailure ?: "The command ran, but the phone still reports the app as $now.")
            }
            recorder.warning = crossUserWarning(otherUsersBefore, otherUserStates(live, user, pkg))
            ActionOutcome(recorder.finish(ActionStatus.SUCCESS, "${action.title} done."), state)
        } catch (e: ActionFailedException) {
            ActionOutcome(recorder.finish(ActionStatus.FAILED, e.message), state)
        } catch (e: UnsupportedActionException) {
            ActionOutcome(recorder.finish(ActionStatus.FAILED, e.message), state)
        } catch (e: AdbException) {
            ActionOutcome(recorder.finish(ActionStatus.FAILED, e.message), state)
        }
    }

    /** Runs the commands for one state transition. */
    private fun step(live: LiveDevice, user: Int, pkg: String, state: LivePackage?, action: PackageAction, recorder: Recorder) {
        if (state == null) throw ActionFailedException("The app disappeared from the phone.")
        val commands = PackageCommands.step(pkg, state.state, action.target, live.props.sdk, user)
        commands.forEachIndexed { index, command ->
            val result = adb.shell(live.adbSerial, command)
            recorder.record(command, result.output)
            val failure = AdbErrors.failure(result, command)
            if (failure != null) {
                recorder.lastFailure = failure
                // Only the first command changes the state; the rest (force-stop) are best-effort.
                if (index == 0) throw ActionFailedException(failure)
            }
        }
    }

    private fun achieved(action: PackageAction, state: LivePackage?): Boolean {
        return when (action) {
            PackageAction.UNINSTALL -> state == null || state.state == PackageState.UNINSTALLED
            PackageAction.DISABLE -> state?.state == PackageState.DISABLED
            PackageAction.RESTORE -> state?.state == PackageState.ENABLED
        }
    }

    /** OEM ROMs sometimes change other users (work profile, dual apps) too; UAD-ng reports that, so do we. */
    private fun otherUserStates(live: LiveDevice, user: Int, pkg: String): Map<Int, PackageState?> {
        if (live.props.sdk < 21) return emptyMap()
        val others = runCatching { adb.listUsers(live.adbSerial) }.getOrDefault(emptyList()).filter { it.id != user }
        return others.mapNotNull { other ->
            runCatching { other.id to stateReader.readOne(live.adbSerial, other.id, live.props.sdk, pkg)?.state }.getOrNull()
        }.toMap()
    }

    private fun crossUserWarning(before: Map<Int, PackageState?>, after: Map<Int, PackageState?>): String? {
        val changes = before.keys.intersect(after.keys).filter { before[it] != after[it] }.map { id ->
            "user $id: ${before[id]?.title ?: "not on phone"} → ${after[id]?.title ?: "not on phone"}"
        }
        return if (changes.isEmpty()) null else "The phone also changed this app for other users (${changes.joinToString("; ")})."
    }

    private inner class Recorder(val request: ActionRequest, val label: String?, val started: Long) {
        var stateBefore: PackageState? = null
        var stateAfter: PackageState? = null
        var warning: String? = null
        var lastFailure: String? = null
        private val commands = mutableListOf<String>()
        private val output = StringBuilder()

        fun record(command: String, result: String) {
            commands += command
            if (output.length < MAX_OUTPUT) output.append("$ ").append(command).append('\n').append(result.take(MAX_OUTPUT)).append('\n')
        }

        fun finish(status: ActionStatus, message: String?): ActionLogEntry {
            val entry = actionLog.insert(
                ActionLogEntry(
                    createdAt = clock.instant(),
                    deviceId = request.deviceId,
                    androidUserId = request.userId,
                    packageName = request.packageName,
                    label = label,
                    action = request.action,
                    beforeSnapshotId = request.beforeSnapshotId,
                    afterSnapshotId = request.afterSnapshotId,
                    stateBefore = stateBefore,
                    stateAfter = stateAfter,
                    status = status,
                    commands = commands.joinToString("\n").ifEmpty { null },
                    output = output.toString().trim().ifEmpty { null },
                    message = message,
                    warning = warning,
                    durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis(),
                ),
            )
            log.info("{} {} (user {}): {} - {}", request.action, request.packageName, request.userId, status, message)
            return entry
        }
    }

    private class ActionFailedException(message: String) : RuntimeException(message)

    companion object {
        private const val MAX_OUTPUT = 20_000
    }
}
