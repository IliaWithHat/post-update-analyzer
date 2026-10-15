package com.ilia.postupdateanalyzer.web.filter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class LocalhostGuardFilterTest {

    private val filter = LocalhostGuardFilter()

    private fun run(method: String, host: String?, vararg headers: Pair<String, String>, serverName: String = "localhost"): Int {
        val request = MockHttpServletRequest(method, "/devices/1/actions")
        request.serverName = serverName
        request.removeHeader("Host")
        host?.let { request.addHeader("Host", it) }
        headers.forEach { (k, v) -> request.addHeader(k, v) }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return if (chain.request != null) 200 else response.status
    }

    @Test
    fun `pages load from localhost names only`() {
        assertThat(run("GET", "localhost:18765")).isEqualTo(200)
        assertThat(run("GET", "127.0.0.1:18765")).isEqualTo(200)
        assertThat(run("GET", "[::1]:18765")).isEqualTo(200)
        assertThat(run("GET", "LOCALHOST")).isEqualTo(200)
        // DNS rebinding: an attacker's domain pointing to 127.0.0.1
        assertThat(run("GET", "evil.example:18765")).isEqualTo(403)
        assertThat(run("GET", "192.168.1.10:18765")).isEqualTo(403)
        // no Host header (HTTP/1.0): the server name decides
        assertThat(run("GET", null)).isEqualTo(200)
        assertThat(run("GET", null, serverName = "evil.example")).isEqualTo(403)
    }

    @Test
    fun `changes need the htmx header and a local origin`() {
        assertThat(run("POST", "127.0.0.1:18765", "HX-Request" to "true", "Origin" to "http://127.0.0.1:18765")).isEqualTo(200)
        assertThat(run("POST", "127.0.0.1:18765", "HX-Request" to "true")).isEqualTo(200)
        assertThat(run("DELETE", "localhost:1", "HX-Request" to "true", "Origin" to "http://localhost:1")).isEqualTo(200)

        // plain HTML form posted by another website
        assertThat(run("POST", "127.0.0.1:18765", "Origin" to "https://evil.example")).isEqualTo(403)
        assertThat(run("POST", "127.0.0.1:18765")).isEqualTo(403)
        assertThat(run("POST", "127.0.0.1:18765", "HX-Request" to "true", "Origin" to "https://evil.example")).isEqualTo(403)
        assertThat(run("POST", "127.0.0.1:18765", "HX-Request" to "true", "Sec-Fetch-Site" to "cross-site")).isEqualTo(403)
        assertThat(run("POST", "127.0.0.1:18765", "HX-Request" to "true", "Origin" to "not a url")).isEqualTo(403)
    }

    @Test
    fun `extracts host names`() {
        assertThat(LocalhostGuardFilter.hostName("localhost:8080")).isEqualTo("localhost")
        assertThat(LocalhostGuardFilter.hostName("[::1]:8080")).isEqualTo("::1")
        assertThat(LocalhostGuardFilter.hostName("127.0.0.1")).isEqualTo("127.0.0.1")
        assertThat(LocalhostGuardFilter.hostName("  ")).isNull()
    }
}
