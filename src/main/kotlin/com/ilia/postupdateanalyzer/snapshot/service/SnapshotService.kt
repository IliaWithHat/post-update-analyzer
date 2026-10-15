package com.ilia.postupdateanalyzer.snapshot.service

import com.ilia.postupdateanalyzer.action.repository.ActionLogRepository
import com.ilia.postupdateanalyzer.device.exception.NotFoundException
import com.ilia.postupdateanalyzer.device.service.DeviceLocks
import com.ilia.postupdateanalyzer.device.service.DeviceService
import com.ilia.postupdateanalyzer.device.service.PackageStateReader
import com.ilia.postupdateanalyzer.metadata.service.AppMetadataCollector
import com.ilia.postupdateanalyzer.snapshot.exception.EmptySnapshotException
import com.ilia.postupdateanalyzer.snapshot.exception.SnapshotInUseException
import com.ilia.postupdateanalyzer.snapshot.model.BuildInfo
import com.ilia.postupdateanalyzer.snapshot.model.NewSnapshot
import com.ilia.postupdateanalyzer.snapshot.model.Snapshot
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotKind
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotPackage
import com.ilia.postupdateanalyzer.snapshot.repository.SnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat

@Service
class SnapshotService(
    private val devices: DeviceService,
    private val locks: DeviceLocks,
    private val stateReader: PackageStateReader,
    private val metadata: AppMetadataCollector,
    private val snapshots: SnapshotRepository,
    private val actionLog: ActionLogRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Reads every package of [userId] from the phone and stores it. */
    fun capture(deviceId: Long, userId: Int, kind: SnapshotKind, note: String?): Snapshot {
        val live = devices.live(deviceId)
        return locks.withLock(live.record, Duration.ZERO, "Another snapshot or action is running on this phone. Wait until it finishes.") {
            val props = devices.freshProps(live)
            val sdk = props.sdk
            val states = stateReader.readAll(live.adbSerial, userId, sdk)
            if (states.isEmpty()) {
                throw EmptySnapshotException(
                    "The phone returned no apps for user $userId. Nothing was saved, so it cannot look like everything was removed.",
                )
            }
            val meta = metadata.collect(live.adbSerial, userId)

            val icons = HashMap<String, ByteArray>()
            val packages = states.values.sortedBy { it.packageName }.map { pkg ->
                val app = meta.apps[pkg.packageName]
                val iconHash = app?.iconPng?.let { png -> sha256(png).also { icons[it] = png } }
                SnapshotPackage(
                    packageName = pkg.packageName,
                    state = pkg.state,
                    system = pkg.system,
                    updatedSystem = app?.updatedSystem,
                    versionCode = pkg.versionCode ?: app?.versionCode,
                    versionName = app?.versionName,
                    label = app?.label?.takeIf { it != pkg.packageName },
                    apkPath = app?.apkPath,
                    firstInstallTime = app?.firstInstallTime,
                    lastUpdateTime = app?.lastUpdateTime,
                    iconHash = iconHash,
                )
            }
            val id = snapshots.insert(
                NewSnapshot(
                    deviceId = live.record.id,
                    androidUserId = userId,
                    kind = kind,
                    note = note?.trim()?.take(500)?.takeIf { it.isNotEmpty() },
                    createdAt = clock.instant(),
                    adbSerial = live.adbSerial,
                    build = BuildInfo(props.fingerprint, props.displayId, props.incremental, props.release, sdk, props.securityPatch),
                    metadataStatus = meta.status,
                    metadataMessage = meta.message,
                    packages = packages,
                    icons = icons,
                ),
            )
            log.info("Snapshot {} of {} (user {}): {} packages, metadata {}", id, live.record.displayName, userId, packages.size, meta.status)
            snapshots.findById(id)!!
        }
    }

    fun get(id: Long): Snapshot {
        return snapshots.findById(id) ?: throw NotFoundException("Snapshot $id not found")
    }

    fun list(deviceId: Long, userId: Int? = null): List<Snapshot> {
        return snapshots.findByDevice(deviceId, userId)
    }

    /** A snapshot that actions were run from stays, so History can always show the comparison of each action. */
    fun delete(id: Long) {
        if (actionLog.countForSnapshot(id) > 0) {
            throw SnapshotInUseException("This snapshot has actions in History, so it cannot be deleted.")
        }
        if (!snapshots.delete(id)) throw NotFoundException("Snapshot $id not found")
    }

    private fun sha256(bytes: ByteArray): String {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
