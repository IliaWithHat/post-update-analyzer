package com.ilia.postupdateanalyzer.device.repository

import com.ilia.postupdateanalyzer.device.model.Device
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class DeviceRepository(private val jdbc: JdbcClient) {

    private val mapper = RowMapper { rs, _ ->
        Device(
            id = rs.getLong("id"),
            serialNo = rs.getString("serial_no"),
            manufacturer = rs.getString("manufacturer"),
            model = rs.getString("model"),
            firstSeenAt = Instant.parse(rs.getString("first_seen_at")),
            lastSeenAt = Instant.parse(rs.getString("last_seen_at")),
        )
    }

    fun findById(id: Long): Device? {
        return jdbc.sql("SELECT * FROM device WHERE id = ?").param(id).query(mapper).optional().orElse(null)
    }

    fun findBySerialNo(serialNo: String): Device? {
        return jdbc.sql("SELECT * FROM device WHERE serial_no = ?").param(serialNo).query(mapper).optional().orElse(null)
    }

    fun findAll(): List<Device> {
        return jdbc.sql("SELECT * FROM device ORDER BY last_seen_at DESC").query(mapper).list()
    }

    /** Inserts or refreshes the device and returns it. */
    fun upsert(serialNo: String, manufacturer: String?, model: String?, now: Instant): Device {
        jdbc.sql(
            """
            INSERT INTO device(serial_no, manufacturer, model, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(serial_no) DO UPDATE SET
                manufacturer = coalesce(excluded.manufacturer, manufacturer),
                model = coalesce(excluded.model, model),
                last_seen_at = excluded.last_seen_at
            """.trimIndent(),
        ).params(listOf(serialNo, manufacturer, model, now.toString(), now.toString())).update()
        return findBySerialNo(serialNo)!!
    }

    data class SnapshotStats(val count: Int, val lastAt: Instant?)

    fun snapshotStats(): Map<Long, SnapshotStats> {
        return jdbc.sql("SELECT device_id, count(*) AS cnt, max(created_at) AS last_at FROM snapshot GROUP BY device_id")
            .query { rs, _ ->
                rs.getLong("device_id") to SnapshotStats(rs.getInt("cnt"), rs.getString("last_at")?.let(Instant::parse))
            }
            .list()
            .toMap()
    }
}
