package com.ilia.postupdateanalyzer.adb.command

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Same cases as UAD-ng's `PackageId` tests, plus shell injection attempts. */
class PackageNamesTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "A.a", "x.X", "org.example", "net.hello", "uwu.owo", "Am0Gu5.Zuz",
            "net.net.net.net.net.net.net.net.net.net.net", "com.github.w1nst0n", "this_.String_.is_.not_.real_", "android",
        ],
    )
    fun `accepts valid package names`(name: String) {
        assertThat(PackageNames.isValid(name)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "   ", ".", "nodots", "com..example", "net.hello.", "org.0example", "org._foobar", "the.🎂.is.a.lie",
            "EXCLAMATION!!!!", "com.foo;reboot", "com.foo && rm -rf /", "com.foo\$(id)", "com.foo`id`", "com.foo bar",
            "com.foo\nreboot", "com.foo|sh", "../../etc.passwd", "com.foo>x",
        ],
    )
    fun `rejects invalid names and shell injection`(name: String) {
        assertThat(PackageNames.isValid(name)).isFalse()
        assertThatThrownBy { PackageNames.requireValid(name) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
