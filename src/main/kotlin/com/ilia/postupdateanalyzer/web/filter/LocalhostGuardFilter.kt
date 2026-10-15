package com.ilia.postupdateanalyzer.web.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI

/**
 * The app can uninstall apps on a connected phone, so only pages served by this app on this computer may use it.
 *
 * - The Host header must be a loopback name. This blocks DNS-rebinding attacks from websites.
 * - Changing requests (POST, DELETE, ...) must come from htmx (`HX-Request` header). Browsers do not let other
 *   websites send custom headers cross-origin without CORS, which this app never allows. A cross-site `Origin`
 *   or `Sec-Fetch-Site` header is rejected as well.
 */
@Component
class LocalhostGuardFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val problem = problem(request)
        if (problem != null) {
            response.status = HttpStatus.FORBIDDEN.value()
            response.contentType = "text/plain;charset=UTF-8"
            response.writer.write(problem)
            return
        }
        chain.doFilter(request, response)
    }

    private fun problem(request: HttpServletRequest): String? {
        // Browsers always send Host; without it (HTTP/1.0 tools) the name the request reached is used.
        val host = hostName(request.getHeader("Host") ?: request.serverName) ?: return "Missing Host header"
        if (host !in LOOPBACK_HOSTS) return "Requests are only accepted from this computer."

        if (request.method in SAFE_METHODS) return null
        if (request.getHeader("HX-Request") != "true") return "This request must come from the app page."
        val origin = request.getHeader("Origin")
        if (origin != null && origin != "null") {
            val originHost = runCatching { URI(origin).host }.getOrNull()?.lowercase()?.removeSurrounding("[", "]")
            if (originHost !in LOOPBACK_HOSTS) return "Cross-site request rejected."
        }
        if (request.getHeader("Sec-Fetch-Site") == "cross-site") return "Cross-site request rejected."
        return null
    }

    companion object {
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")

        /** "localhost:8080" -> "localhost", "[::1]:8080" -> "::1". */
        fun hostName(hostHeader: String?): String? {
            val value = hostHeader?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return if (value.startsWith("[")) value.substringAfter('[').substringBefore(']') else value.substringBefore(':')
        }
    }
}
