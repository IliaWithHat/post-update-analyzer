package com.ilia.postupdateanalyzer.settings.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class SettingsRepository(private val jdbc: JdbcClient) {

    fun get(key: String): String? {
        return jdbc.sql("SELECT value FROM app_setting WHERE key = ?")
            .param(key)
            .query(String::class.java)
            .optional()
            .orElse(null)
    }

    /** Stores [value]; `null` removes the setting. */
    fun put(key: String, value: String?) {
        if (value == null) {
            jdbc.sql("DELETE FROM app_setting WHERE key = ?").param(key).update()
        } else {
            jdbc.sql("INSERT INTO app_setting(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
                .params(key, value)
                .update()
        }
    }
}
