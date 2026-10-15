package com.ilia.postupdateanalyzer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties("app")
data class AppProperties(
    /** Folder with the database and logs. Set by the desktop launcher. */
    val dataDir: Path,
    /** Optional adb path used when none is saved in Settings. */
    val adbPath: String? = null,
    val commandTimeout: Duration = Duration.ofSeconds(60),
    val transferTimeout: Duration = Duration.ofMinutes(5),
    val helperTimeout: Duration = Duration.ofMinutes(3),
    val iconSize: Int = 96,
)
