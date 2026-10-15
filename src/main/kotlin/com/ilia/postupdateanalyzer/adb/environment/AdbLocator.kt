package com.ilia.postupdateanalyzer.adb.environment

import com.ilia.postupdateanalyzer.adb.model.AdbLocation
import com.ilia.postupdateanalyzer.adb.model.AdbLookup
import com.ilia.postupdateanalyzer.platform.OperatingSystem
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/**
 * Finds the adb binary: configured path, `ADB` env var, `PATH`, `ANDROID_HOME`/`ANDROID_SDK_ROOT`,
 * then the default Android SDK locations of Android Studio.
 *
 * @param probe runs `adb version` for a candidate and returns its first line, or null if it does not work.
 */
class AdbLocator(
    private val env: Map<String, String> = System.getenv(),
    private val os: OperatingSystem = OperatingSystem.current(),
    private val userHome: Path = Path(System.getProperty("user.home")),
    private val probe: (Path) -> String?,
) {

    fun locate(configuredPath: String?): AdbLookup {
        val problems = mutableListOf<String>()
        val searched = mutableListOf<Path>()

        if (!configuredPath.isNullOrBlank()) {
            val configured = normalizeConfigured(configuredPath.trim())
            searched.add(configured)
            val version = configured.takeIf { isRunnable(it) }?.let(probe)
            if (version != null) {
                return AdbLookup(AdbLocation(configured, "Settings", version), problems, searched)
            }
            problems += "The ADB path from Settings does not work: $configured"
        }

        for ((source, candidate) in candidates()) {
            searched.add(candidate)
            if (!isRunnable(candidate)) continue
            val version = probe(candidate)
            if (version != null) {
                return AdbLookup(AdbLocation(candidate, source, version), problems, searched.distinct())
            }
            problems += "Found $candidate but it does not run."
        }
        return AdbLookup(null, problems, searched.distinct())
    }

    /** Accepts either the adb file or the platform-tools folder. */
    private fun normalizeConfigured(value: String): Path {
        val path = Path(value.removeSurrounding("\"")).absolute()
        return if (Files.isDirectory(path)) path.resolve(os.adbFileName) else path
    }

    private fun candidates(): List<Pair<String, Path>> {
        val result = mutableListOf<Pair<String, Path>>()
        env["ADB"]?.takeIf { it.isNotBlank() }?.let { result += "ADB environment variable" to Path(it) }

        val pathVariable = env.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value.orEmpty()
        val separator = if (os == OperatingSystem.WINDOWS) ';' else ':'
        pathVariable.split(separator).filter { it.isNotBlank() }.forEach { dir ->
            runCatching { Path(dir.trim().removeSurrounding("\"")).resolve(os.adbFileName) }
                .onSuccess { result += "PATH" to it }
        }

        listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { variable ->
            env[variable]?.takeIf { it.isNotBlank() }?.let {
                result += variable to Path(it).resolve("platform-tools").resolve(os.adbFileName)
            }
        }

        when (os) {
            OperatingSystem.WINDOWS -> {
                env["LOCALAPPDATA"]?.let { result += "Android Studio SDK" to Path(it, "Android", "Sdk", "platform-tools", "adb.exe") }
                result += "Android Studio SDK" to userHome.resolve("AppData/Local/Android/Sdk/platform-tools/adb.exe")
                result += "platform-tools" to Path("C:\\platform-tools\\adb.exe")
            }

            OperatingSystem.MAC -> {
                result += "Android Studio SDK" to userHome.resolve("Library/Android/sdk/platform-tools/adb")
                result += "Homebrew" to Path("/opt/homebrew/bin/adb")
            }

            else -> {
                result += "Android Studio SDK" to userHome.resolve("Android/Sdk/platform-tools/adb")
                result += "platform-tools" to userHome.resolve("platform-tools/adb")
                result += "System" to Path("/usr/bin/adb")
                result += "System" to Path("/usr/lib/android-sdk/platform-tools/adb")
            }
        }
        return result.distinctBy { it.second.normalize() }
    }

    private fun isRunnable(path: Path): Boolean {
        return runCatching { path.isRegularFile() && (os == OperatingSystem.WINDOWS || path.isExecutable()) }.getOrDefault(false)
    }
}
