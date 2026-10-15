package com.ilia.postupdateanalyzer

import com.ilia.postupdateanalyzer.desktop.launcher.DesktopLauncher
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class PostUpdateAnalyzerApplication

fun main(args: Array<String>) {
    DesktopLauncher.launch(args)
}
