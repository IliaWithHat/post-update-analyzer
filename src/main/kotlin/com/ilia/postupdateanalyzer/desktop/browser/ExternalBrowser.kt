package com.ilia.postupdateanalyzer.desktop.browser

import org.springframework.stereotype.Component
import java.net.URI

/** The user's default browser, as a bean so tests can replace it. */
@Component
class ExternalBrowser {
    fun open(uri: URI): Boolean {
        return BrowserOpener.open(uri)
    }
}
