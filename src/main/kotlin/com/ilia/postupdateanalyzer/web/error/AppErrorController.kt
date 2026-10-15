package com.ilia.postupdateanalyzer.web.error

import com.ilia.postupdateanalyzer.web.htmx.Htmx
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.ModelAndView

/**
 * Errors that happen outside controllers (e.g. while rendering a page) end up here.
 * Like [WebErrorHandler]: a toast for htmx requests, the error page otherwise.
 */
@Controller
class AppErrorController : ErrorController {

    @RequestMapping("/error")
    fun error(request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        val status = (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int)
            ?.let { HttpStatus.resolve(it) } ?: HttpStatus.INTERNAL_SERVER_ERROR
        val message = when (status) {
            HttpStatus.NOT_FOUND -> "Page not found."
            HttpStatus.FORBIDDEN -> "Access denied."
            else -> "Unexpected error (${status.value()}). See the log file for details."
        }
        response.status = status.value()
        if (Htmx.isHtmx(request)) {
            response.setHeader("HX-Reswap", "none")
            return ModelAndView("fragments :: toast-oob").addObject("toastMessage", message).addObject("toastKind", "error")
        }
        return ModelAndView("error").addObject("status", status.value()).addObject("message", message)
    }
}
