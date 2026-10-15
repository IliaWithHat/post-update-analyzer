package com.ilia.postupdateanalyzer.device.service

import com.ilia.postupdateanalyzer.adb.client.AdbClient
import com.ilia.postupdateanalyzer.adb.command.PackageCommands
import com.ilia.postupdateanalyzer.adb.command.PackageNames
import com.ilia.postupdateanalyzer.adb.model.PackageState
import com.ilia.postupdateanalyzer.device.model.LivePackage
import org.springframework.stereotype.Component

/**
 * Reads package states the way UAD-ng does, with plain `pm list packages`:
 * installed and in `-d` = disabled, installed = enabled, only in `-u` = uninstalled for this user.
 */
@Component
class PackageStateReader(private val adb: AdbClient) {

    fun readAll(serial: String, userId: Int, sdk: Int): Map<String, LivePackage> {
        return read(serial, userId, sdk, filter = null)
    }

    /** @return null when the package is not on the phone at all. */
    fun readOne(serial: String, userId: Int, sdk: Int, packageName: String): LivePackage? {
        PackageNames.requireValid(packageName)
        return read(serial, userId, sdk, filter = packageName)[packageName]
    }

    private fun read(serial: String, userId: Int, sdk: Int, filter: String?): Map<String, LivePackage> {
        val user = PackageCommands.userFlag(sdk, userId)
        val withVersions = if (sdk >= 28) listOf("--show-versioncode") else emptyList()
        // pm's filter is a substring match, so results are matched exactly below.
        val all = adb.listPackages(serial, user, listOf("-u") + withVersions, filter)
        val installed = adb.listPackages(serial, user, emptyList(), filter).mapTo(HashSet()) { it.packageName }
        val disabled = adb.listPackages(serial, user, listOf("-d"), filter).mapTo(HashSet()) { it.packageName }
        val system = adb.listPackages(serial, user, listOf("-s", "-u"), filter).mapTo(HashSet()) { it.packageName }

        val versions = all.associate { it.packageName to it.versionCode }
        val names = (versions.keys + installed).filter { filter == null || it == filter }
        return names.associateWith { name ->
            val state = when {
                name in disabled -> PackageState.DISABLED
                name in installed -> PackageState.ENABLED
                else -> PackageState.UNINSTALLED
            }
            LivePackage(name, state, system = name in system, versionCode = versions[name])
        }
    }
}
