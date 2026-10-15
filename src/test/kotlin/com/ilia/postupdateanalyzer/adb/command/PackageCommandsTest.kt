package com.ilia.postupdateanalyzer.adb.command

import com.ilia.postupdateanalyzer.adb.exception.UnsupportedActionException
import com.ilia.postupdateanalyzer.adb.model.PackageState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Mirrors UAD-ng `apply_pkg_state_commands` for every SDK level and state transition. */
class PackageCommandsTest {

    private val pkg = "com.vendor.bloat"

    @ParameterizedTest(name = "SDK {0}: {1} -> {2}")
    @MethodSource("transitions")
    fun `builds the same commands as UAD-ng`(sdk: Int, current: PackageState, wanted: PackageState, expected: List<String>) {
        assertThat(PackageCommands.step(pkg, current, wanted, sdk, 0)).isEqualTo(expected)
    }

    @Test
    fun `adds the user flag from Android 5 and not before`() {
        assertThat(PackageCommands.step(pkg, PackageState.DISABLED, PackageState.ENABLED, 21, 10))
            .containsExactly("pm enable --user 10 $pkg")
        assertThat(PackageCommands.step(pkg, PackageState.DISABLED, PackageState.ENABLED, 20, 10))
            .containsExactly("pm enable $pkg")
    }

    @Test
    fun `disabling needs Android 6`() {
        assertThatThrownBy { PackageCommands.step(pkg, PackageState.ENABLED, PackageState.DISABLED, 22, 0) }
            .isInstanceOf(UnsupportedActionException::class.java)
            .hasMessageContaining("Android 6.0")
    }

    @Test
    fun `refuses ancient Android versions`() {
        assertThatThrownBy { PackageCommands.step(pkg, PackageState.ENABLED, PackageState.UNINSTALLED, 18, 0) }
            .isInstanceOf(UnsupportedActionException::class.java)
    }

    @Test
    fun `refuses invalid package names before building a command`() {
        assertThatThrownBy { PackageCommands.step("com.x; reboot", PackageState.ENABLED, PackageState.UNINSTALLED, 33, 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    companion object {
        private const val P = "com.vendor.bloat"

        @JvmStatic
        fun transitions(): List<Arguments> {
            val result = mutableListOf<Arguments>()
            for (sdk in 19..36) {
                val u = if (sdk >= 21) " --user 0" else ""
                val restore = when {
                    sdk >= 23 -> "cmd package install-existing"
                    sdk >= 21 -> "pm unhide"
                    else -> "pm unblock"
                }
                val uninstall = when {
                    sdk >= 23 -> "pm uninstall"
                    sdk >= 21 -> "pm hide"
                    else -> "pm block"
                }
                for (state in PackageState.entries) {
                    result += Arguments.of(sdk, state, state, emptyList<String>())
                }
                result += Arguments.of(sdk, PackageState.DISABLED, PackageState.ENABLED, listOf("pm enable$u $P"))
                result += Arguments.of(sdk, PackageState.UNINSTALLED, PackageState.ENABLED, listOf("$restore$u $P"))
                result += Arguments.of(sdk, PackageState.ENABLED, PackageState.UNINSTALLED, listOf("$uninstall$u $P"))
                result += Arguments.of(sdk, PackageState.DISABLED, PackageState.UNINSTALLED, listOf("$uninstall$u $P"))
                // first step of Uninstalled -> Disabled is bringing the app back
                result += Arguments.of(sdk, PackageState.UNINSTALLED, PackageState.DISABLED, listOf("$restore$u $P"))
                if (sdk >= 23) {
                    result += Arguments.of(sdk, PackageState.ENABLED, PackageState.DISABLED, listOf("pm disable-user$u $P", "am force-stop$u $P"))
                }
            }
            return result
        }
    }
}
