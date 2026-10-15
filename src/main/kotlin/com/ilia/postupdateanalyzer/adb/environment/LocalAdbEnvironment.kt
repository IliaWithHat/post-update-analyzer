package com.ilia.postupdateanalyzer.adb.environment

import com.ilia.postupdateanalyzer.adb.executor.ProcessAdbExecutor
import com.ilia.postupdateanalyzer.adb.model.AdbStatus
import com.ilia.postupdateanalyzer.adb.parser.AdbParsers
import com.ilia.postupdateanalyzer.config.AppProperties
import com.ilia.postupdateanalyzer.settings.repository.SettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Duration

@Service
class LocalAdbEnvironment(
    private val settings: SettingsRepository,
    private val properties: AppProperties,
) : AdbEnvironment {

    private val log = LoggerFactory.getLogger(javaClass)
    private val locator = AdbLocator(probe = ::probe)

    @Volatile
    private var cached: AdbStatus? = null

    override fun status(refresh: Boolean): AdbStatus {
        val current = cached
        if (current != null && !refresh && current.available) return current
        return lookup()
    }

    override fun configure(path: String?): AdbStatus {
        settings.put(ADB_PATH_KEY, path?.trim()?.takeIf { it.isNotEmpty() })
        return lookup()
    }

    @Synchronized
    private fun lookup(): AdbStatus {
        val configured = settings.get(ADB_PATH_KEY) ?: properties.adbPath
        val lookup = locator.locate(configured)
        val status = AdbStatus(lookup.location, configured, lookup.problems, lookup.searched)
        if (status.location != cached?.location) {
            log.info("ADB: {}", status.location?.let { "${it.path} (${it.version}, from ${it.source})" } ?: "not found")
        }
        cached = status
        return status
    }

    private fun probe(path: Path): String? {
        return runCatching {
            val result = ProcessAdbExecutor { listOf(path.toString()) }.execute(listOf("version"), Duration.ofSeconds(15))
            if (!result.succeeded) return null
            AdbParsers.lines(result.output).firstOrNull { it.startsWith("Android Debug Bridge") }
        }.getOrNull()
    }

    companion object {
        const val ADB_PATH_KEY = "adb.path"
    }
}
