package com.ilia.postupdateanalyzer.web.controller

import com.ilia.postupdateanalyzer.compare.model.ChangeType
import com.ilia.postupdateanalyzer.compare.service.ComparisonService
import com.ilia.postupdateanalyzer.web.view.ComparisonViews
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class ComparisonController(
    private val comparisons: ComparisonService,
    private val views: ComparisonViews,
) {

    @GetMapping("/compare")
    fun compare(@RequestParam before: Long, @RequestParam after: Long, model: Model): String {
        val page = views.page(comparisons.compare(before, after))
        model.addAttribute("page", page)
        model.addAttribute("comparison", page.comparison)
        model.addAttribute("changeTypes", ChangeType.entries)
        return "comparison"
    }
}
