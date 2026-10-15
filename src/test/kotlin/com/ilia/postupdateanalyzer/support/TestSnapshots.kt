package com.ilia.postupdateanalyzer.support

import com.ilia.postupdateanalyzer.metadata.model.MetadataStatus
import com.ilia.postupdateanalyzer.snapshot.model.BuildInfo
import com.ilia.postupdateanalyzer.snapshot.model.NewSnapshot
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotKind
import com.ilia.postupdateanalyzer.snapshot.repository.SnapshotRepository
import java.time.Instant

/** Snapshots written straight to the database (no adb), for tests that run actions without a real comparison. */
object TestSnapshots {

    /** A "before" and an "after" snapshot without packages: (before id, after id). */
    fun emptyPair(repository: SnapshotRepository, deviceId: Long, userId: Int = 0): Pair<Long, Long> {
        val before = repository.insert(empty(deviceId, userId, SnapshotKind.BEFORE_UPDATE, Instant.parse("2025-12-01T10:00:00Z")))
        val after = repository.insert(empty(deviceId, userId, SnapshotKind.AFTER_UPDATE, Instant.parse("2025-12-02T10:00:00Z")))
        return before to after
    }

    private fun empty(deviceId: Long, userId: Int, kind: SnapshotKind, createdAt: Instant): NewSnapshot {
        return NewSnapshot(
            deviceId = deviceId,
            androidUserId = userId,
            kind = kind,
            note = null,
            createdAt = createdAt,
            adbSerial = "test",
            build = BuildInfo(null, null, null, null, null, null),
            metadataStatus = MetadataStatus.UNAVAILABLE,
            metadataMessage = null,
            packages = emptyList(),
            icons = emptyMap(),
        )
    }
}
