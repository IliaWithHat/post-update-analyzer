package com.ilia.postupdateanalyzer.desktop.launcher

import com.ilia.postupdateanalyzer.platform.OperatingSystem
import java.nio.file.Path
import kotlin.io.path.Path

object DataDirectories {
    /**
     * Windows: `%LOCALAPPDATA%\PostUpdateAnalyzer`, Linux: `$XDG_DATA_HOME/post-update-analyzer`
     * (default `~/.local/share/post-update-analyzer`), macOS: `~/Library/Application Support/PostUpdateAnalyzer`.
     */
    fun default(os: OperatingSystem, env: Map<String, String>, userHome: Path): Path {
        return when (os) {
            OperatingSystem.WINDOWS ->
                (env["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }?.let { Path(it) } ?: userHome.resolve("AppData").resolve("Local"))
                    .resolve("PostUpdateAnalyzer")

            OperatingSystem.MAC -> userHome.resolve("Library").resolve("Application Support").resolve("PostUpdateAnalyzer")

            else -> (env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let { Path(it) }?.takeIf { it.isAbsolute }
                ?: userHome.resolve(".local").resolve("share"))
                .resolve("post-update-analyzer")
        }
    }
}
