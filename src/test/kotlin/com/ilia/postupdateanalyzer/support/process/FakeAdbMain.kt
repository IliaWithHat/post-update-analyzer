package com.ilia.postupdateanalyzer.support.process

import kotlin.system.exitProcess

/**
 * A stand-in for the adb binary, started as a real process by `ProcessAdbExecutorTest`.
 * Running it with `java` works the same on Linux and Windows.
 */
object FakeAdbMain {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "echo" -> print(args.drop(1).joinToString(" "))
            "both" -> {
                print("out")
                System.out.flush()
                System.err.print("err")
            }
            "exit" -> {
                System.err.print("failing")
                exitProcess(args[1].toInt())
            }
            "sleep" -> Thread.sleep(args[1].toLong())
            "big" -> repeat(args[1].toInt()) { println("package:com.example.app$it versionCode:$it") }
            "unicode" -> System.out.write("Налаштування 设置 🎉\n".toByteArray(Charsets.UTF_8))
            "crlf" -> print("a\r\nb\r\n")
            // Like `adb start-server`: a background child keeps our stdout open after we exit.
            "daemon" -> {
                val java = ProcessHandle.current().info().command().orElse("java")
                ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), FakeAdbMain::class.java.name, "sleep", "8000")
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                print("* daemon started successfully")
            }
            else -> {
                System.err.print("unknown")
                exitProcess(1)
            }
        }
        System.out.flush()
    }
}
