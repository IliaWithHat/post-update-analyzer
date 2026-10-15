package com.ilia.postupdateanalyzer.device.service

import com.ilia.postupdateanalyzer.device.exception.DeviceBusyException
import com.ilia.postupdateanalyzer.device.model.Device
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** One operation at a time per phone: a snapshot must not run while an app is being uninstalled. */
@Component
class DeviceLocks {
    private val locks = ConcurrentHashMap<Long, ReentrantLock>()

    fun <T> withLock(device: Device, wait: Duration, busyMessage: String, block: () -> T): T {
        val lock = locks.computeIfAbsent(device.id) { ReentrantLock() }
        if (!lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS)) {
            throw DeviceBusyException(busyMessage)
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
