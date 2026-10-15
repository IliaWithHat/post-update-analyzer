package com.ilia.postupdateanalyzer.adb.executor

import com.ilia.postupdateanalyzer.adb.exception.AdbNotFoundException
import com.ilia.postupdateanalyzer.adb.model.AdbResult
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Starts the real `adb` binary.
 *
 * @param adbCommand returns the command prefix, normally `[/path/to/adb]`; tests pass a fake program here.
 */
class ProcessAdbExecutor(private val adbCommand: () -> List<String>) : AdbExecutor {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(args: List<String>, timeout: Duration): AdbResult {
        val command = adbCommand() + args
        val started = System.nanoTime()
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            throw AdbNotFoundException("Cannot start ADB (${command.first()}): ${e.message}")
        }
        process.outputStream.close()
        val stdout = StreamCollector(process.inputStream)
        val stderr = StreamCollector(process.errorStream)

        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        // When adb starts its background server, the server can inherit our pipes and keep them open,
        // so never wait for end-of-stream forever once the adb client itself has exited.
        stdout.await(STREAM_GRACE_MS)
        stderr.await(STREAM_GRACE_MS)

        val result = AdbResult(
            exitCode = if (finished) process.exitValue() else -1,
            stdout = clean(stdout.text()),
            stderr = clean(stderr.text()),
            timedOut = !finished,
            durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis(),
        )
        log.debug("adb {} -> exit={} timedOut={} in {} ms", args.joinToString(" "), result.exitCode, result.timedOut, result.durationMs)
        return result
    }

    private class StreamCollector(stream: InputStream) {
        private val buffer = ByteArrayOutputStream()
        private val thread = Thread.ofVirtual().start {
            val chunk = ByteArray(8192)
            try {
                stream.use {
                    while (true) {
                        val read = it.read(chunk)
                        if (read < 0) break
                        synchronized(buffer) { buffer.write(chunk, 0, read) }
                    }
                }
            } catch (_: IOException) {
                // stream closed because the process was killed
            }
        }

        fun await(millis: Long) {
            thread.join(Duration.ofMillis(millis))
        }

        fun text(): String {
            return synchronized(buffer) { buffer.toString(Charsets.UTF_8) }
        }
    }

    companion object {
        private const val STREAM_GRACE_MS = 3_000L

        internal fun clean(text: String): String {
            return text.replace("\r", "").trimEnd()
        }
    }
}
