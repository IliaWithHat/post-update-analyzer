package com.ilia.postupdateanalyzer.web.controller

import com.ilia.postupdateanalyzer.snapshot.repository.SnapshotRepository
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

@RestController
class IconController(private val snapshots: SnapshotRepository) {

    /** Icons are addressed by the SHA-256 of their content, so they never change and can be cached forever. */
    @GetMapping("/icons/{hash}.png")
    fun icon(@PathVariable hash: String): ResponseEntity<ByteArray> {
        if (!HASH.matches(hash)) return ResponseEntity.badRequest().build()
        val png = snapshots.icon(hash) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .body(png)
    }

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")
    }
}
