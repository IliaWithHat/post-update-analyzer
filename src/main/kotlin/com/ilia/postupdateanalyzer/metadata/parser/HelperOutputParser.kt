package com.ilia.postupdateanalyzer.metadata.parser

import com.ilia.postupdateanalyzer.adb.command.PackageNames
import com.ilia.postupdateanalyzer.metadata.model.AppMetadata
import com.ilia.postupdateanalyzer.metadata.model.HelperOutput
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Base64

/** Parses `PUA-BEGIN|n`, `PUA|<base64 json>`, `PUA-END` and `PUA-FATAL|<base64 text>` lines; everything else is ignored. */
object HelperOutputParser {

    const val MAX_ICON_BYTES = 256 * 1024
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val mapper = JsonMapper.builder().build()
    private val decoder = Base64.getDecoder()

    fun parse(output: String): HelperOutput {
        val apps = LinkedHashMap<String, AppMetadata>()
        var announced: Int? = null
        var complete = false
        var fatal: String? = null
        var badLines = 0

        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("PUA-BEGIN|") -> announced = line.substringAfter('|').toIntOrNull()
                line == "PUA-END" -> complete = true
                line.startsWith("PUA-FATAL|") -> fatal = decodeText(line.substringAfter('|')) ?: "unknown error"
                line.startsWith("PUA|") -> {
                    val app = decodeText(line.substringAfter('|'))?.let(::toMetadata)
                    if (app == null) badLines++ else apps[app.packageName] = app
                }
            }
        }
        return HelperOutput(apps, announced, complete, fatal, badLines)
    }

    private fun decodeText(base64: String): String? {
        return runCatching { String(decoder.decode(base64), Charsets.UTF_8) }.getOrNull()
    }

    private fun toMetadata(json: String): AppMetadata? {
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        val packageName = node.text("packageName")?.takeIf { PackageNames.isValid(it) } ?: return null
        return AppMetadata(
            packageName = packageName,
            label = node.text("label")?.trim()?.takeIf { it.isNotEmpty() }?.take(200),
            versionCode = node.long("versionCode"),
            versionName = node.text("versionName")?.take(100),
            system = node.bool("system"),
            updatedSystem = node.bool("updatedSystem"),
            firstInstallTime = node.long("firstInstallTime"),
            lastUpdateTime = node.long("lastUpdateTime"),
            apkPath = node.text("apkPath"),
            iconPng = node.text("icon")?.let(::decodeIcon),
            error = node.text("error"),
        )
    }

    /** Accepts only real PNG data of a sane size. */
    fun decodeIcon(base64: String): ByteArray? {
        val bytes = runCatching { decoder.decode(base64) }.getOrNull() ?: return null
        if (bytes.size > MAX_ICON_BYTES || bytes.size < PNG_SIGNATURE.size) return null
        if (!bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) return null
        return bytes
    }

    private fun JsonNode.text(field: String): String? {
        return get(field)?.takeIf { it.isString }?.asString()
    }

    private fun JsonNode.long(field: String): Long? {
        return get(field)?.takeIf { it.isNumber }?.asLong()
    }

    private fun JsonNode.bool(field: String): Boolean? {
        return get(field)?.takeIf { it.isBoolean }?.asBoolean()
    }
}
