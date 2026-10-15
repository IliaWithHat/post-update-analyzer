package com.ilia.postupdateanalyzer.metadata.service

import com.ilia.postupdateanalyzer.adb.client.AdbClient
import com.ilia.postupdateanalyzer.adb.exception.AdbException
import com.ilia.postupdateanalyzer.config.AppProperties
import com.ilia.postupdateanalyzer.metadata.model.HelperOutput
import com.ilia.postupdateanalyzer.metadata.model.MetadataResult
import com.ilia.postupdateanalyzer.metadata.model.MetadataStatus
import com.ilia.postupdateanalyzer.metadata.parser.HelperOutputParser
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Reads app names, icons and versions by running a small helper on the phone with `app_process`
 * (no app is installed). The helper file is deleted from the phone afterwards.
 */
@Component
class AppMetadataCollector(private val adb: AdbClient, private val properties: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val helper: ByteArray? by lazy {
        val resource = ClassPathResource(HELPER_RESOURCE)
        if (resource.exists()) resource.inputStream.use { it.readAllBytes() } else null
    }

    fun collect(serial: String, userId: Int): MetadataResult {
        val bytes = helper ?: return unavailable("The metadata helper is missing from this build.")
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).take(12)
        val remote = "/data/local/tmp/pua-helper-$hash.jar"
        return try {
            val local = localCopy(bytes, hash)
            val push = adb.push(serial, local, remote)
            if (!push.succeeded) return unavailable("Could not copy the helper to the phone: ${push.output}")

            val command = "CLASSPATH=$remote app_process /system/bin $MAIN_CLASS --user $userId --icon-size ${properties.iconSize}"
            val result = adb.shell(serial, command, properties.helperTimeout)
            interpret(HelperOutputParser.parse(result.stdout), result.output)
        } catch (e: AdbException) {
            unavailable(e.message ?: "adb error")
        } catch (e: Exception) {
            log.warn("Metadata helper failed", e)
            unavailable(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { adb.shell(serial, "rm -f $remote") }
        }
    }

    private fun interpret(output: HelperOutput, rawOutput: String): MetadataResult {
        if (output.fatal != null) return unavailable("The helper failed on the phone: ${output.fatal}")
        if (output.apps.isEmpty()) {
            return unavailable("The helper returned no data. ${rawOutput.take(500)}".trim())
        }
        val failed = output.apps.values.count { it.error != null }
        val missingIcons = output.apps.values.count { it.iconPng == null }
        val problems = buildList {
            if (!output.complete) add("output was cut off")
            if (output.badLines > 0) add("${output.badLines} unreadable lines")
            if (failed > 0) add("$failed apps could not be read")
            if (missingIcons > 0) add("$missingIcons apps without icon")
        }
        val status = if (!output.complete || output.badLines > 0 || failed > 0) MetadataStatus.PARTIAL else MetadataStatus.FULL
        return MetadataResult(output.apps, status, problems.joinToString(", ").ifEmpty { null })
    }

    private fun localCopy(bytes: ByteArray, hash: String): Path {
        val dir = properties.dataDir.resolve("tmp")
        Files.createDirectories(dir)
        val file = dir.resolve("pua-helper-$hash.jar")
        if (!Files.exists(file) || Files.size(file) != bytes.size.toLong()) Files.write(file, bytes)
        return file
    }

    private fun unavailable(message: String): MetadataResult {
        log.warn("App names/icons unavailable: {}", message)
        return MetadataResult(emptyMap(), MetadataStatus.UNAVAILABLE, message)
    }

    companion object {
        const val HELPER_RESOURCE = "helper/pua-helper.jar"
        const val MAIN_CLASS = "com.ilia.postupdateanalyzer.helper.Main"
    }
}
