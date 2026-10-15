package com.ilia.postupdateanalyzer.adb.parser

import com.ilia.postupdateanalyzer.adb.model.AdbResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AdbErrorsTest {

    private fun result(stdout: String, exit: Int = 0, stderr: String = ""): AdbResult {
        return AdbResult(exit, stdout, stderr)
    }

    @Test
    fun `real success outputs are not failures`() {
        assertThat(AdbErrors.failure(result("Success"), "pm uninstall --user 0 com.a.b")).isNull()
        assertThat(AdbErrors.failure(result("Package com.a.b new state: disabled-user"), "pm disable-user --user 0 com.a.b")).isNull()
        assertThat(AdbErrors.failure(result("Package com.a.b installed for user: 0"), "cmd package install-existing --user 0 com.a.b")).isNull()
        assertThat(AdbErrors.failure(result(""), "am force-stop --user 0 com.a.b")).isNull()
    }

    @Test
    fun `a package name containing Error is not a failure`() {
        val command = "pm disable-user --user 0 com.foo.ErrorReporter"

        assertThat(AdbErrors.failure(result("Package com.foo.ErrorReporter new state: disabled-user"), command)).isNull()
    }

    @Test
    fun `old phones exit with 0 even on failure, so the text decides`() {
        assertThat(AdbErrors.failure(result("Failure [not installed for 0]"), "pm uninstall --user 0 com.a.b"))
            .contains("not installed for this user")
        assertThat(AdbErrors.failure(result("Error: java.lang.SecurityException"), "pm enable com.a.b")).isNotNull()
    }

    @Test
    fun `non-zero exit codes and timeouts are failures`() {
        assertThat(AdbErrors.failure(result("", exit = 255, stderr = "boom"), "pm enable com.a.b")).contains("boom")
        assertThat(AdbErrors.failure(AdbResult(-1, "", "", timedOut = true), "pm enable com.a.b")).contains("did not answer in time")
    }

    @Test
    fun `explains known OEM errors`() {
        assertThat(AdbErrors.friendly("Failure [DELETE_FAILED_USER_RESTRICTED]", "x")).contains("Knox").contains("disabling")
        assertThat(AdbErrors.friendly("Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]", "x")).contains("device policy")
        assertThat(AdbErrors.friendly("java.lang.SecurityException: Shell cannot change component state for com.a.b", "x"))
            .contains("does not allow")
        assertThat(AdbErrors.friendly("something odd", "pm enable com.a.b")).isEqualTo("pm enable com.a.b -> something odd")
    }

    @Test
    fun `query output with words like Error inside data is fine`() {
        val listing = "package:com.foo.ErrorReporter\npackage:com.bar.FailureTracker\npackage:com.x.ExceptionHandler"

        assertThat(AdbErrors.queryFailure(result(listing), "pm list packages")).isNull()
        assertThat(AdbErrors.queryFailure(result("[ro.reason]: [Error in boot]"), "getprop")).isNull()
    }

    @Test
    fun `query errors are detected by line or exit code`() {
        assertThat(AdbErrors.queryFailure(result("Error: unknown option -z"), "pm list packages -z")).isNotNull()
        assertThat(AdbErrors.queryFailure(result("\nException occurred while executing 'list':\njava.lang.SecurityException"), "pm list packages"))
            .isNotNull()
        assertThat(AdbErrors.queryFailure(result("", exit = 1), "pm list packages")).isNotNull()
    }

    @Test
    fun `recognizes when adb cannot reach the phone`() {
        assertThat(AdbErrors.isDeviceGone(result("", 1, "adb: device 'R58M' not found"))).isTrue()
        assertThat(AdbErrors.isDeviceGone(result("", 1, "error: device offline"))).isTrue()
        assertThat(AdbErrors.isDeviceGone(result("", 1, "error: device unauthorized.\nThis adb server's \$ADB_VENDOR_KEYS is not set"))).isTrue()
        assertThat(AdbErrors.isDeviceGone(result("", 1, "adb: no devices/emulators found"))).isTrue()
        assertThat(AdbErrors.isDeviceGone(result("", 1, "error: closed"))).isTrue()
        // errors from tools on the phone are not "device gone"
        assertThat(AdbErrors.isDeviceGone(result("Failure [not installed for 0]", 1))).isFalse()
        assertThat(AdbErrors.isDeviceGone(result("", 255, "Error: package not found"))).isFalse()
        assertThat(AdbErrors.isDeviceGone(result("Success"))).isFalse()
    }
}
