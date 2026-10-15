package com.ilia.postupdateanalyzer.adb.executor

import com.ilia.postupdateanalyzer.adb.exception.AdbNotFoundException
import com.ilia.postupdateanalyzer.adb.parser.AdbParsers
import com.ilia.postupdateanalyzer.support.process.FakeAdbMain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/** Runs a real child process (a JVM running [FakeAdbMain]) to test timeouts, exit codes and output handling. */
class ProcessAdbExecutorTest {

    private val java = ProcessHandle.current().info().command().orElse("java")
    private val executor = ProcessAdbExecutor { listOf(java, "-cp", System.getProperty("java.class.path"), FakeAdbMain::class.java.name) }
    private val timeout = Duration.ofSeconds(30)

    @Test
    fun `captures stdout and exit code`() {
        val result = executor.execute(listOf("echo", "hello", "phone"), timeout)

        assertThat(result.exitCode).isZero()
        assertThat(result.stdout).isEqualTo("hello phone")
        assertThat(result.succeeded).isTrue()
    }

    @Test
    fun `keeps stdout and stderr apart`() {
        val result = executor.execute(listOf("both"), timeout)

        assertThat(result.stdout).isEqualTo("out")
        assertThat(result.stderr).isEqualTo("err")
        assertThat(result.output).isEqualTo("out\nerr")
    }

    @Test
    fun `reports non-zero exit codes`() {
        val result = executor.execute(listOf("exit", "3"), timeout)

        assertThat(result.exitCode).isEqualTo(3)
        assertThat(result.stderr).isEqualTo("failing")
        assertThat(result.succeeded).isFalse()
    }

    @Test
    fun `kills commands that take too long`() {
        val started = System.nanoTime()

        val result = executor.execute(listOf("sleep", "60000"), Duration.ofSeconds(2))

        assertThat(result.timedOut).isTrue()
        assertThat(result.succeeded).isFalse()
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20))
    }

    @Test
    fun `reads large output without blocking`() {
        val result = executor.execute(listOf("big", "20000"), timeout)

        assertThat(result.stdout.lines()).hasSize(20000)
        assertThat(AdbParsers.parsePackageList(result.stdout)).hasSize(20000)
    }

    @Test
    fun `decodes UTF-8 and strips carriage returns`() {
        assertThat(executor.execute(listOf("unicode"), timeout).stdout).isEqualTo("Налаштування 设置 🎉")
        assertThat(executor.execute(listOf("crlf"), timeout).stdout).isEqualTo("a\nb")
    }

    @Test
    fun `does not hang when a background server keeps the output pipe open`() {
        val started = System.nanoTime()

        val result = executor.execute(listOf("daemon"), timeout)

        assertThat(result.exitCode).isZero()
        assertThat(result.stdout).contains("daemon started successfully")
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(7))
    }

    @Test
    fun `missing binary means adb not found`() {
        val missing = ProcessAdbExecutor { listOf("/definitely/not/here/adb") }

        assertThatThrownBy { missing.execute(listOf("version"), timeout) }.isInstanceOf(AdbNotFoundException::class.java)
    }
}
