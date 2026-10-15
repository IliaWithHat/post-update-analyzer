package com.ilia.postupdateanalyzer.compare.model

import com.ilia.postupdateanalyzer.adb.model.PackageState
import com.ilia.postupdateanalyzer.adb.model.PackageState.DISABLED
import com.ilia.postupdateanalyzer.adb.model.PackageState.ENABLED
import com.ilia.postupdateanalyzer.adb.model.PackageState.UNINSTALLED
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotPackage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class ChangeTypeTest {

    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @MethodSource("matrix")
    fun `every before-after combination has the expected change type`(before: PackageState?, after: PackageState?, expected: ChangeType) {
        assertThat(ChangeType.of(before, after)).isEqualTo(expected)
    }

    @Test
    fun `only unchanged counts as not changed`() {
        assertThat(ChangeType.entries.filter { !it.changed }).containsExactly(ChangeType.UNCHANGED)
    }

    private fun pkg(state: PackageState, version: Long? = 1, label: String? = null, system: Boolean = true, icon: String? = null): SnapshotPackage {
        return SnapshotPackage("com.a.b", state, system, null, version, version?.let { "$it.0" }, label, null, null, null, icon)
    }

    @Test
    fun `detects version upgrades and downgrades but not unknown versions`() {
        assertThat(PackageDiff("com.a.b", pkg(ENABLED, 1), pkg(ENABLED, 2), ChangeType.UNCHANGED).versionChange).isEqualTo(VersionChange.UPGRADED)
        assertThat(PackageDiff("com.a.b", pkg(ENABLED, 5), pkg(ENABLED, 2), ChangeType.UNCHANGED).versionChange).isEqualTo(VersionChange.DOWNGRADED)
        assertThat(PackageDiff("com.a.b", pkg(ENABLED, 5), pkg(ENABLED, 5), ChangeType.UNCHANGED).versionChange).isEqualTo(VersionChange.NONE)
        assertThat(PackageDiff("com.a.b", pkg(ENABLED, null), pkg(ENABLED, 5), ChangeType.UNCHANGED).versionChange).isEqualTo(VersionChange.NONE)
        assertThat(PackageDiff("com.a.b", null, pkg(ENABLED, 5), ChangeType.INSTALLED).versionChange).isEqualTo(VersionChange.NONE)
    }

    @Test
    fun `label, icon and system flag prefer the newer snapshot and fall back to the package name`() {
        val renamed = PackageDiff("com.a.b", pkg(ENABLED, label = "Old", icon = "h1"), pkg(ENABLED, label = "New", icon = "h2", system = false), ChangeType.UNCHANGED)
        assertThat(renamed.label).isEqualTo("New")
        assertThat(renamed.iconHash).isEqualTo("h2")
        assertThat(renamed.system).isFalse()

        val removed = PackageDiff("com.a.b", pkg(ENABLED, label = "Old", icon = "h1"), null, ChangeType.REMOVED)
        assertThat(removed.label).isEqualTo("Old")
        assertThat(removed.iconHash).isEqualTo("h1")

        val noName = PackageDiff("com.a.b", pkg(ENABLED), pkg(DISABLED), ChangeType.DISABLED)
        assertThat(noName.label).isEqualTo("com.a.b")
    }

    companion object {
        @JvmStatic
        fun matrix(): List<Arguments> {
            return listOf(
                Arguments.of(null, UNINSTALLED, ChangeType.ADDED_INACTIVE),
                Arguments.of(null, DISABLED, ChangeType.INSTALLED),
                Arguments.of(null, ENABLED, ChangeType.INSTALLED),
                Arguments.of(UNINSTALLED, null, ChangeType.REMOVED),
                Arguments.of(UNINSTALLED, UNINSTALLED, ChangeType.UNCHANGED),
                Arguments.of(UNINSTALLED, DISABLED, ChangeType.RESTORED),
                Arguments.of(UNINSTALLED, ENABLED, ChangeType.RESTORED),
                Arguments.of(DISABLED, null, ChangeType.REMOVED),
                Arguments.of(DISABLED, UNINSTALLED, ChangeType.UNINSTALLED),
                Arguments.of(DISABLED, DISABLED, ChangeType.UNCHANGED),
                Arguments.of(DISABLED, ENABLED, ChangeType.RE_ENABLED),
                Arguments.of(ENABLED, null, ChangeType.REMOVED),
                Arguments.of(ENABLED, UNINSTALLED, ChangeType.UNINSTALLED),
                Arguments.of(ENABLED, DISABLED, ChangeType.DISABLED),
                Arguments.of(ENABLED, ENABLED, ChangeType.UNCHANGED),
            )
        }
    }
}
