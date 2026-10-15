package com.ilia.postupdateanalyzer.web.htmx

import jakarta.servlet.http.HttpServletRequest

object Htmx {
    fun isHtmx(request: HttpServletRequest): Boolean {
        return request.getHeader("HX-Request") == "true"
    }
}
