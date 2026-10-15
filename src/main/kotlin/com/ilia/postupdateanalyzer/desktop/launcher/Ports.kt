package com.ilia.postupdateanalyzer.desktop.launcher

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

object Ports {
    /** The preferred port when it is free, otherwise 0 (= let the server pick a free one). */
    fun pick(preferred: Int): Int {
        return try {
            ServerSocket(preferred, 0, InetAddress.getLoopbackAddress()).use { preferred }
        } catch (_: IOException) {
            0
        }
    }
}
