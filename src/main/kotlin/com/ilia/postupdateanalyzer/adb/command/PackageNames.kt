package com.ilia.postupdateanalyzer.adb.command

/**
 * Package names are put into device shell commands, so only valid Android package names are allowed.
 * Same rules as UAD-ng's `PackageId`: at least two dot-separated parts, each starting with a letter and
 * containing only letters, digits and underscores (plus the special `android` package).
 */
object PackageNames {
    private val PART = Regex("[A-Za-z][A-Za-z0-9_]*")

    fun isValid(name: String): Boolean {
        if (name == "android") return true
        val parts = name.split('.')
        return parts.size >= 2 && parts.all { PART.matches(it) }
    }

    fun requireValid(name: String): String {
        require(isValid(name)) { "Invalid package name: '$name'" }
        return name
    }
}
