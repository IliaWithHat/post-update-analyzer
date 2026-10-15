package com.ilia.postupdateanalyzer.desktop.launcher

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Makes sure only one copy of the app runs per data folder; a second launch just opens the browser. */
class InstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val portFile: Path,
) : AutoCloseable {

    fun writePort(port: Int) {
        Files.writeString(portFile, port.toString())
    }

    override fun close() {
        runCatching { Files.deleteIfExists(portFile) }
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        private const val LOCK_FILE = "instance.lock"
        private const val PORT_FILE = "instance.port"

        /** @return null when another instance holds the lock. */
        fun acquire(dataDir: Path): InstanceLock? {
            Files.createDirectories(dataDir)
            val channel = FileChannel.open(dataDir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (_: IOException) {
                null
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return InstanceLock(channel, lock, dataDir.resolve(PORT_FILE))
        }

        fun readPort(dataDir: Path): Int? {
            return runCatching { Files.readString(dataDir.resolve(PORT_FILE)).trim().toInt() }.getOrNull()
        }
    }
}
