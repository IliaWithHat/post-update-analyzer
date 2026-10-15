package com.ilia.postupdateanalyzer.action.repository

import com.ilia.postupdateanalyzer.action.model.ActionLogEntry
import com.ilia.postupdateanalyzer.action.model.ActionStatus
import com.ilia.postupdateanalyzer.adb.model.PackageAction
import com.ilia.postupdateanalyzer.adb.model.PackageState
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class ActionLogRepository(private val jdbc: JdbcClient) {

    private val mapper = RowMapper { rs, _ ->
        ActionLogEntry(
            id = rs.getLong("id"),
            createdAt = Instant.parse(rs.getString("created_at")),
            deviceId = rs.getLong("device_id"),
            androidUserId = rs.getInt("android_user_id"),
            packageName = rs.getString("package_name"),
            label = rs.getString("label"),
            action = PackageAction.valueOf(rs.getString("action")),
            beforeSnapshotId = rs.getLong("before_snapshot_id"),
            afterSnapshotId = rs.getLong("after_snapshot_id"),
            stateBefore = rs.getString("state_before")?.let(PackageState::valueOf),
            stateAfter = rs.getString("state_after")?.let(PackageState::valueOf),
            status = ActionStatus.valueOf(rs.getString("status")),
            commands = rs.getString("commands"),
            output = rs.getString("output"),
            message = rs.getString("message"),
            warning = rs.getString("warning"),
            durationMs = rs.getLong("duration_ms"),
        )
    }

    fun insert(entry: ActionLogEntry): ActionLogEntry {
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """
            INSERT INTO action_log(created_at, device_id, android_user_id, package_name, label, action, before_snapshot_id,
                after_snapshot_id, state_before, state_after, status, commands, output, message, warning, duration_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).params(
            listOf(
                entry.createdAt.toString(), entry.deviceId, entry.androidUserId, entry.packageName, entry.label, entry.action.name,
                entry.beforeSnapshotId, entry.afterSnapshotId, entry.stateBefore?.name, entry.stateAfter?.name, entry.status.name,
                entry.commands, entry.output, entry.message, entry.warning, entry.durationMs,
            ),
        ).update(keys, "id")
        return entry.copy(id = keys.key!!.toLong())
    }

    /** Newest first. */
    fun find(deviceId: Long?, status: ActionStatus?, limit: Int = 500): List<ActionLogEntry> {
        return jdbc.sql(
            """
            SELECT * FROM action_log
            WHERE (:device IS NULL OR device_id = :device) AND (:status IS NULL OR status = :status)
            ORDER BY created_at DESC, id DESC LIMIT :limit
            """.trimIndent(),
        ).param("device", deviceId).param("status", status?.name).param("limit", limit).query(mapper).list()
    }

    /** Snapshot id -> number of actions run from a comparison that uses it. Snapshots without actions are missing. */
    fun countBySnapshot(deviceId: Long): Map<Long, Int> {
        return jdbc.sql(
            """
            SELECT snapshot_id, count(*) AS cnt FROM (
                SELECT before_snapshot_id AS snapshot_id FROM action_log WHERE device_id = :device
                UNION ALL
                SELECT after_snapshot_id FROM action_log WHERE device_id = :device
            ) GROUP BY snapshot_id
            """.trimIndent(),
        ).param("device", deviceId).query { rs, _ -> rs.getLong("snapshot_id") to rs.getInt("cnt") }.list().toMap()
    }

    /** Number of actions run from a comparison that uses the snapshot. */
    fun countForSnapshot(snapshotId: Long): Int {
        return jdbc.sql("SELECT count(*) FROM action_log WHERE before_snapshot_id = :id OR after_snapshot_id = :id")
            .param("id", snapshotId)
            .query(Int::class.java)
            .single()
    }
}
