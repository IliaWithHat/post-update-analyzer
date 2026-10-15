package com.ilia.postupdateanalyzer.snapshot.repository

import com.ilia.postupdateanalyzer.adb.model.PackageState
import com.ilia.postupdateanalyzer.metadata.model.MetadataStatus
import com.ilia.postupdateanalyzer.snapshot.model.BuildInfo
import com.ilia.postupdateanalyzer.snapshot.model.NewSnapshot
import com.ilia.postupdateanalyzer.snapshot.model.Snapshot
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotKind
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotPackage
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant

@Repository
class SnapshotRepository(private val jdbc: JdbcClient, private val template: JdbcTemplate) {

    private val snapshotMapper = RowMapper { rs, _ ->
        Snapshot(
            id = rs.getLong("id"),
            deviceId = rs.getLong("device_id"),
            androidUserId = rs.getInt("android_user_id"),
            kind = SnapshotKind.valueOf(rs.getString("kind")),
            note = rs.getString("note"),
            createdAt = Instant.parse(rs.getString("created_at")),
            adbSerial = rs.getString("adb_serial"),
            build = BuildInfo(
                fingerprint = rs.getString("build_fingerprint"),
                displayId = rs.getString("build_display_id"),
                incremental = rs.getString("build_incremental"),
                androidRelease = rs.getString("android_release"),
                sdkInt = rs.intOrNull("sdk_int"),
                securityPatch = rs.getString("security_patch"),
            ),
            packageCount = rs.getInt("package_count"),
            metadataStatus = MetadataStatus.valueOf(rs.getString("metadata_status")),
            metadataMessage = rs.getString("metadata_message"),
        )
    }

    private val packageMapper = RowMapper { rs, _ ->
        SnapshotPackage(
            packageName = rs.getString("package_name"),
            state = PackageState.valueOf(rs.getString("state")),
            system = rs.getInt("is_system") != 0,
            updatedSystem = rs.intOrNull("is_updated_system")?.let { it != 0 },
            versionCode = rs.longOrNull("version_code"),
            versionName = rs.getString("version_name"),
            label = rs.getString("label"),
            apkPath = rs.getString("apk_path"),
            firstInstallTime = rs.longOrNull("first_install_time"),
            lastUpdateTime = rs.longOrNull("last_update_time"),
            iconHash = rs.getString("icon_hash"),
        )
    }

    fun findById(id: Long): Snapshot? {
        return jdbc.sql("SELECT * FROM snapshot WHERE id = ?").param(id).query(snapshotMapper).optional().orElse(null)
    }

    /** Newest first. */
    fun findByDevice(deviceId: Long, userId: Int? = null): List<Snapshot> {
        return jdbc.sql(
            """
            SELECT * FROM snapshot WHERE device_id = :device AND (:user IS NULL OR android_user_id = :user)
            ORDER BY created_at DESC, id DESC
            """.trimIndent(),
        ).param("device", deviceId).param("user", userId).query(snapshotMapper).list()
    }

    fun packages(snapshotId: Long): List<SnapshotPackage> {
        return jdbc.sql("SELECT * FROM snapshot_package WHERE snapshot_id = ? ORDER BY package_name")
            .param(snapshotId)
            .query(packageMapper)
            .list()
    }

    /** Saves the snapshot, its packages and new icons in one transaction. */
    @Transactional
    fun insert(snapshot: NewSnapshot): Long {
        snapshot.icons.forEach { (hash, png) ->
            jdbc.sql("INSERT OR IGNORE INTO app_icon(hash, png) VALUES (?, ?)").params(hash, png).update()
        }
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            """
            INSERT INTO snapshot(device_id, android_user_id, kind, note, created_at, adb_serial, build_fingerprint,
                build_display_id, build_incremental, android_release, sdk_int, security_patch, package_count,
                metadata_status, metadata_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).params(
            listOf(
                snapshot.deviceId, snapshot.androidUserId, snapshot.kind.name, snapshot.note, snapshot.createdAt.toString(),
                snapshot.adbSerial, snapshot.build.fingerprint, snapshot.build.displayId, snapshot.build.incremental,
                snapshot.build.androidRelease, snapshot.build.sdkInt, snapshot.build.securityPatch, snapshot.packages.size,
                snapshot.metadataStatus.name, snapshot.metadataMessage,
            ),
        ).update(keys, "id")
        val id = keys.key!!.toLong()

        template.batchUpdate(
            """
            INSERT INTO snapshot_package(snapshot_id, package_name, state, is_system, is_updated_system, version_code,
                version_name, label, apk_path, first_install_time, last_update_time, icon_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            snapshot.packages.map {
                arrayOf<Any?>(
                    id, it.packageName, it.state.name, if (it.system) 1 else 0, it.updatedSystem?.let { u -> if (u) 1 else 0 },
                    it.versionCode, it.versionName, it.label, it.apkPath, it.firstInstallTime, it.lastUpdateTime, it.iconHash,
                )
            },
        )
        return id
    }

    @Transactional
    fun delete(id: Long): Boolean {
        val deleted = jdbc.sql("DELETE FROM snapshot WHERE id = ?").param(id).update() > 0
        if (deleted) {
            jdbc.sql("DELETE FROM app_icon WHERE hash NOT IN (SELECT icon_hash FROM snapshot_package WHERE icon_hash IS NOT NULL)")
                .update()
        }
        return deleted
    }

    fun icon(hash: String): ByteArray? {
        return jdbc.sql("SELECT png FROM app_icon WHERE hash = ?").param(hash).query(ByteArray::class.java).optional().orElse(null)
    }

    /** Label and icon of a package from the most recent snapshot that knows them. */
    fun latestAppInfo(deviceId: Long, packageName: String): SnapshotPackage? {
        return jdbc.sql(
            """
            SELECT sp.* FROM snapshot_package sp JOIN snapshot s ON s.id = sp.snapshot_id
            WHERE s.device_id = ? AND sp.package_name = ?
            ORDER BY (sp.label IS NOT NULL) DESC, s.created_at DESC LIMIT 1
            """.trimIndent(),
        ).params(deviceId, packageName).query(packageMapper).optional().orElse(null)
    }

    private fun ResultSet.intOrNull(column: String): Int? {
        return getInt(column).takeUnless { wasNull() }
    }

    private fun ResultSet.longOrNull(column: String): Long? {
        return getLong(column).takeUnless { wasNull() }
    }
}
