package com.ilia.postupdateanalyzer.support.fake

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Advances one second on every call, so snapshots and log entries have a clear order. */
class TickingClock(private var now: Instant = START) : Clock() {
    @Synchronized
    override fun instant(): Instant {
        now = now.plusSeconds(1)
        return now
    }

    @Synchronized
    fun reset() {
        now = START
    }

    override fun getZone(): ZoneId {
        return ZoneOffset.UTC
    }

    override fun withZone(zone: ZoneId?): Clock {
        return this
    }

    companion object {
        val START: Instant = Instant.parse("2026-01-01T10:00:00Z")
    }
}
