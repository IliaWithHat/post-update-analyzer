package com.ilia.postupdateanalyzer.web.controller

import com.ilia.postupdateanalyzer.desktop.browser.ExternalBrowser
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * The app window has no tabs or address bar, so `app.js` sends links to other sites here and they open in
 * the user's normal browser, like links in Slack do.
 */
@RestController
class LinkController(private val browser: ExternalBrowser) {

    @PostMapping("/open-link")
    fun open(@RequestParam url: String): ResponseEntity<Void> {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank()) return ResponseEntity.badRequest().build()
        return if (browser.open(uri)) ResponseEntity.noContent().build() else ResponseEntity.internalServerError().build()
    }
}
