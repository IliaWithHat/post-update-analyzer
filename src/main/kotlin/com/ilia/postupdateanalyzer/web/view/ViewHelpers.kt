package com.ilia.postupdateanalyzer.web.view

import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Formatting helpers for templates, used as `${@fmt.time(x)}`. */
@Component("fmt")
class ViewHelpers {
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun time(instant: Instant?): String {
        return instant?.atZone(ZoneId.systemDefault())?.format(dateTime) ?: "—"
    }

    fun duration(millis: Long): String {
        return if (millis < 1000) "$millis ms" else "%.1f s".format(millis / 1000.0)
    }

    /** "1 action", "3 actions". */
    fun count(count: Int, noun: String): String {
        return if (count == 1) "1 $noun" else "$count ${noun}s"
    }

    /** First letter for the placeholder icon when the real icon is unknown. */
    fun initial(text: String?): String {
        return text?.trim()?.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    }

    /** Stable color for the placeholder icon. */
    fun hue(text: String?): Int {
        return Math.floorMod((text ?: "").hashCode(), 360)
    }
}
