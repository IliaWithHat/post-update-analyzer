package com.ilia.postupdateanalyzer.support.fake

import com.ilia.postupdateanalyzer.adb.model.AdbResult
import com.ilia.postupdateanalyzer.adb.model.AndroidUser
import com.ilia.postupdateanalyzer.adb.model.DeviceConnectionState
import com.ilia.postupdateanalyzer.adb.model.PackageState
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * A simulated phone that understands the adb shell commands the app sends and changes state like a real
 * Android 13 emulator does (verified against `halimqarroum/docker-android:api-33`).
 */
class FakeDevice(
    val serial: String,
    serialNo: String = serial,
    var sdk: Int = 33,
    model: String = "Pixel 7",
    manufacturer: String = "Google",
) {
    var state: DeviceConnectionState = DeviceConnectionState.DEVICE
    val transportId: String = nextTransportId.incrementAndGet().toString()
    val props = linkedMapOf(
        "ro.serialno" to serialNo,
        "ro.product.manufacturer" to manufacturer,
        "ro.product.model" to model,
        "ro.build.version.sdk" to sdk.toString(),
        "ro.build.version.release" to "13",
        "ro.build.fingerprint" to "google/panther/panther:13/TQ1A.230105.002/9325679:user/release-keys",
        "ro.build.display.id" to "TQ1A.230105.002",
        "ro.build.version.incremental" to "9325679",
        "ro.build.version.security_patch" to "2023-01-05",
    )
    val users = mutableListOf(AndroidUser(0, "Owner", running = true))

    /** Users whose packages cannot be listed (like Samsung Secure Folder). */
    val protectedUsers = mutableSetOf<Int>()
    var currentUser = 0
    val packages = linkedMapOf<String, FakePackage>()
    private val installed = HashMap<Pair<Int, String>, Boolean>()
    private val enabled = HashMap<Pair<Int, String>, Boolean>()

    /** Command (or command prefix) -> output that makes it fail, e.g. Knox restrictions. */
    val failures = linkedMapOf<String, String>()

    /** Commands that are accepted but do nothing (e.g. an OEM that silently re-enables apps). */
    val ignored = mutableSetOf<String>()
    var helperWorks = true
    var helperOutputOverride: String? = null
    val pushedFiles = mutableSetOf<String>()

    fun buildFingerprint(fingerprint: String, displayId: String) {
        props["ro.build.fingerprint"] = fingerprint
        props["ro.build.display.id"] = displayId
    }

    fun add(
        name: String,
        state: PackageState = PackageState.ENABLED,
        system: Boolean = true,
        label: String? = null,
        versionCode: Long = 1,
        icon: ByteArray? = TestPngs.png(name),
        user: Int = 0,
    ): FakeDevice {
        packages[name] = FakePackage(name, system, versionCode, "$versionCode.0", label, icon)
        set(name, state, user)
        return this
    }

    fun set(name: String, state: PackageState, user: Int = 0) {
        installed[user to name] = state != PackageState.UNINSTALLED
        enabled[user to name] = state != PackageState.DISABLED
    }

    fun remove(name: String) {
        packages.remove(name)
        installed.keys.removeIf { it.second == name }
        enabled.keys.removeIf { it.second == name }
    }

    /** null = not on the phone. */
    fun state(name: String, user: Int = 0): PackageState? {
        if (name !in packages) return null
        return when {
            installed[user to name] != true -> PackageState.UNINSTALLED
            enabled[user to name] == false -> PackageState.DISABLED
            else -> PackageState.ENABLED
        }
    }

    internal fun shell(command: String): AdbResult {
        failures.entries.firstOrNull { command.startsWith(it.key) }?.let { return fail(it.value) }
        if (ignored.any { command.startsWith(it) }) return ok("")
        val tokens = command.trim().split(Regex("\\s+"))
        val user = tokens.indexOf("--user").takeIf { it >= 0 }?.let { tokens[it + 1].toInt() } ?: 0
        val pkg = tokens.last()
        return when {
            command == "getprop" -> ok(props.entries.joinToString("\n") { "[${it.key}]: [${it.value}]" })
            command == "pm list users" ->
                ok("Users:\n" + users.joinToString("\n") { "\tUserInfo{${it.id}:${it.name}:c13}" + if (it.running) " running" else "" })
            command == "am get-current-user" -> ok(currentUser.toString())
            command.startsWith("pm list packages") -> listPackages(tokens, user)
            command.startsWith("pm uninstall") || command.startsWith("pm hide") || command.startsWith("pm block") -> uninstall(pkg, user)
            command.startsWith("pm disable-user") -> {
                if (pkg !in packages) return unknownPackage("disable-user", pkg)
                enabled[user to pkg] = false
                ok("Package $pkg new state: disabled-user")
            }
            command.startsWith("pm enable") -> {
                if (pkg !in packages) return unknownPackage("enable", pkg)
                enabled[user to pkg] = true
                ok("Package $pkg new state: enabled")
            }
            command.startsWith("am force-stop") -> ok("")
            command.startsWith("cmd package install-existing") || command.startsWith("pm unhide") || command.startsWith("pm unblock") -> {
                if (pkg !in packages) {
                    return AdbResult(1, "android.content.pm.PackageManager\$NameNotFoundException: Package $pkg doesn't exist", "")
                }
                installed[user to pkg] = true
                ok("Package $pkg installed for user: $user")
            }
            command.startsWith("rm -f ") -> {
                pushedFiles.remove(tokens[2])
                ok("")
            }
            command.startsWith("CLASSPATH=") -> helper(command, tokens)
            else -> AdbResult(127, "", "/system/bin/sh: ${tokens.first()}: inaccessible or not found")
        }
    }

    private fun listPackages(tokens: List<String>, user: Int): AdbResult {
        if (user in protectedUsers) {
            return AdbResult(255, "", "Exception occurred while executing 'list':\njava.lang.SecurityException: Shell does not have permission to access user $user")
        }
        val flags = tokens.drop(3).filter { it.startsWith("-") }.toSet()
        val filter = tokens.drop(3).lastOrNull()?.takeIf { !it.startsWith("-") && it.toIntOrNull() == null }
        val lines = packages.values
            .filter { filter == null || it.name.contains(filter) }
            .filter { p ->
                val state = state(p.name, user)
                val includeUninstalled = "-u" in flags
                (includeUninstalled || state != PackageState.UNINSTALLED) &&
                    ("-d" !in flags || state == PackageState.DISABLED) &&
                    ("-e" !in flags || state == PackageState.ENABLED) &&
                    ("-s" !in flags || p.system) &&
                    ("-3" !in flags || !p.system)
            }
            .map { p -> "package:${p.name}" + if ("--show-versioncode" in flags) " versionCode:${p.versionCode}" else "" }
        return ok(lines.joinToString("\n"))
    }

    private fun uninstall(pkg: String, user: Int): AdbResult {
        if (pkg !in packages || installed[user to pkg] != true) return AdbResult(1, "Failure [not installed for $user]", "")
        installed[user to pkg] = false
        val p = packages.getValue(pkg)
        // A user app installed only for this user is deleted completely, like on a real phone.
        if (!p.system && users.none { installed[it.id to pkg] == true }) remove(pkg)
        return ok("Success")
    }

    private fun helper(command: String, tokens: List<String>): AdbResult {
        val jar = command.substringAfter("CLASSPATH=").substringBefore(' ')
        if (jar !in pushedFiles) return AdbResult(1, "", "Error: Could not find or load main class")
        helperOutputOverride?.let { return ok(it) }
        if (!helperWorks) {
            return ok("PUA-FATAL|" + Base64.getEncoder().encodeToString("java.lang.SecurityException: blocked by ROM".toByteArray()))
        }
        val user = tokens.indexOf("--user").takeIf { it >= 0 }?.let { tokens[it + 1].toInt() } ?: 0
        return ok(helperOutput(user))
    }

    /** What the real helper prints for this phone. */
    fun helperOutput(user: Int = 0): String {
        val lines = packages.values.map { p ->
            val json = buildString {
                append("{\"packageName\":\"").append(p.name).append('"')
                p.label?.let { append(",\"label\":\"").append(it.replace("\\", "\\\\").replace("\"", "\\\"")).append('"') }
                append(",\"versionCode\":").append(p.versionCode)
                p.versionName?.let { append(",\"versionName\":\"").append(it).append('"') }
                append(",\"system\":").append(p.system)
                append(",\"installed\":").append(installed[user to p.name] == true)
                append(",\"firstInstallTime\":1700000000000,\"lastUpdateTime\":1700000000000")
                p.icon?.let { append(",\"icon\":\"").append(Base64.getEncoder().encodeToString(it)).append('"') }
                append('}')
            }
            "PUA|" + Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        }
        return (listOf("PUA-BEGIN|${lines.size}") + lines + "PUA-END").joinToString("\n")
    }

    private fun unknownPackage(command: String, pkg: String): AdbResult {
        return AdbResult(
            255,
            "",
            "Exception occurred while executing '$command':\njava.lang.IllegalArgumentException: Unknown package: $pkg",
        )
    }

    companion object {
        private val nextTransportId = AtomicInteger()

        fun ok(stdout: String): AdbResult {
            return AdbResult(0, stdout, "")
        }

        fun fail(output: String): AdbResult {
            return AdbResult(1, output, "")
        }
    }
}
