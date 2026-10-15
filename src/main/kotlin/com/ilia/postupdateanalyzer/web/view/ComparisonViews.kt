package com.ilia.postupdateanalyzer.web.view

import com.ilia.postupdateanalyzer.compare.model.Comparison
import com.ilia.postupdateanalyzer.device.service.DeviceService
import com.ilia.postupdateanalyzer.device.service.PackageStateReader
import com.ilia.postupdateanalyzer.web.model.AppRow
import com.ilia.postupdateanalyzer.web.model.ComparisonPage
import org.springframework.stereotype.Component

@Component
class ComparisonViews(
    private val devices: DeviceService,
    private val stateReader: PackageStateReader,
) {

    fun page(comparison: Comparison): ComparisonPage {
        val live = devices.liveOrNull(comparison.device)
        var liveError: String? = null
        val states = live?.let {
            runCatching { stateReader.readAll(it.adbSerial, comparison.after.androidUserId, it.props.sdk) }
                .onFailure { e -> liveError = e.message }
                .getOrNull()
        }
        val rows = comparison.diffs.map { diff ->
            AppRow(diff, connected = states != null, current = states?.get(diff.packageName))
        }
        return ComparisonPage(comparison, rows, live, liveError)
    }
}
