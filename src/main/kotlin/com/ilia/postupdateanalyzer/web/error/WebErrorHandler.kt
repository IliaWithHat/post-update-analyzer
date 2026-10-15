package com.ilia.postupdateanalyzer.web.error

import com.ilia.postupdateanalyzer.adb.exception.AdbException
import com.ilia.postupdateanalyzer.adb.exception.AdbNotFoundException
import com.ilia.postupdateanalyzer.adb.exception.UnsupportedActionException
import com.ilia.postupdateanalyzer.compare.exception.InvalidComparisonException
import com.ilia.postupdateanalyzer.device.exception.DeviceBusyException
import com.ilia.postupdateanalyzer.device.exception.DeviceNotConnectedException
import com.ilia.postupdateanalyzer.device.exception.NotFoundException
import com.ilia.postupdateanalyzer.snapshot.exception.EmptySnapshotException
import com.ilia.postupdateanalyzer.snapshot.exception.SnapshotInUseException
import com.ilia.postupdateanalyzer.web.htmx.Htmx
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * htmx requests get an error toast (and no other change on the page); normal page loads get the error page.
 */
@ControllerAdvice
class WebErrorHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(Exception::class)
    fun handle(e: Exception, request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        val (status, message) = describe(e)
        if (status.is5xxServerError) log.error("Request {} failed", request.requestURI, e) else log.info("{}: {}", request.requestURI, message)

        response.status = status.value()
        if (Htmx.isHtmx(request)) {
            response.setHeader("HX-Reswap", "none")
            return ModelAndView("fragments :: toast-oob").addObject("toastMessage", message).addObject("toastKind", "error")
        }
        return ModelAndView("error")
            .addObject("status", status.value())
            .addObject("message", message)
            .addObject("adbMissing", e is AdbNotFoundException)
    }

    private fun describe(e: Exception): Pair<HttpStatus, String> {
        return when (e) {
            is NotFoundException -> HttpStatus.NOT_FOUND to e.message.orEmpty()
            is NoResourceFoundException -> HttpStatus.NOT_FOUND to "Page not found."
            is DeviceNotConnectedException, is DeviceBusyException, is SnapshotInUseException -> HttpStatus.CONFLICT to e.message.orEmpty()
            is InvalidComparisonException, is IllegalArgumentException, is UnsupportedActionException -> HttpStatus.BAD_REQUEST to e.message.orEmpty()
            is MissingServletRequestParameterException, is MethodArgumentTypeMismatchException -> HttpStatus.BAD_REQUEST to "Invalid request: ${e.message}"
            is EmptySnapshotException -> HttpStatus.UNPROCESSABLE_CONTENT to e.message.orEmpty()
            is AdbNotFoundException -> HttpStatus.SERVICE_UNAVAILABLE to e.message.orEmpty()
            is AdbException -> HttpStatus.BAD_GATEWAY to "ADB error: ${e.message}"
            else -> HttpStatus.INTERNAL_SERVER_ERROR to "Unexpected error: ${e.message ?: e.javaClass.simpleName}. See the log file for details."
        }
    }
}
