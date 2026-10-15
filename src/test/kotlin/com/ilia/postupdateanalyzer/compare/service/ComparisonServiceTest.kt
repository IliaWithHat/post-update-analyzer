package com.ilia.postupdateanalyzer.compare.service

import com.ilia.postupdateanalyzer.adb.model.AndroidUser
import com.ilia.postupdateanalyzer.adb.model.PackageState
import com.ilia.postupdateanalyzer.compare.exception.InvalidComparisonException
import com.ilia.postupdateanalyzer.compare.model.ChangeType
import com.ilia.postupdateanalyzer.compare.model.VersionChange
import com.ilia.postupdateanalyzer.device.exception.NotFoundException
import com.ilia.postupdateanalyzer.device.service.DeviceService
import com.ilia.postupdateanalyzer.snapshot.model.SnapshotKind
import com.ilia.postupdateanalyzer.snapshot.service.SnapshotService
import com.ilia.postupdateanalyzer.support.IntegrationTest
import com.ilia.postupdateanalyzer.support.fake.FakeDevice
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class ComparisonServiceTest : IntegrationTest() {

    @Autowired
    lateinit var comparisons: ComparisonService

    @Autowired
    lateinit var snapshots: SnapshotService

    @Autowired
    lateinit var devices: DeviceService

    private fun id(phone: FakeDevice): Long {
        return devices.scan().connected.single { it.adb.serial == phone.serial }.record!!.id
    }

    /** The vendor update scenario: the user had debloated, the update undid parts of it and added new apps. */
    @Test
    fun `finds what the update changed`() {
        val phone = phone()
            .add("com.vendor.store", PackageState.UNINSTALLED, label = "Vendor Store")
            .add("com.vendor.cloud", PackageState.DISABLED, label = "Vendor Cloud")
            .add("com.vendor.old", label = "Old Thing")
            .add("com.vendor.ghost", PackageState.UNINSTALLED)
        val device = id(phone)
        val before = snapshots.capture(device, 0, SnapshotKind.BEFORE_UPDATE, null)

        phone.buildFingerprint("google/panther/panther:14/UP1A/1:user/release-keys", "UP1A")
        phone.set("com.vendor.store", PackageState.ENABLED) // reinstalled
        phone.set("com.vendor.cloud", PackageState.ENABLED) // re-enabled
        phone.set("com.android.chrome", PackageState.DISABLED) // disabled by update
        phone.set("com.facebook.appmanager", PackageState.UNINSTALLED) // removed for user
        phone.remove("com.vendor.old") // gone
        phone.remove("com.vendor.ghost")
        phone.add("com.vendor.newtrash", label = "Trash", system = false) // new
        phone.add("com.vendor.newsilent", PackageState.UNINSTALLED) // new, not installed for this user
        phone.packages.getValue("com.android.settings").versionCode = 2 // only updated
        val after = snapshots.capture(device, 0, SnapshotKind.AFTER_UPDATE, null)

        val comparison = comparisons.compare(before.id, after.id)

        val changes = comparison.diffs.associate { it.packageName to it.change }
        assertThat(changes).containsAllEntriesOf(
            mapOf(
                "com.vendor.store" to ChangeType.RESTORED,
                "com.vendor.cloud" to ChangeType.RE_ENABLED,
                "com.android.chrome" to ChangeType.DISABLED,
                "com.facebook.appmanager" to ChangeType.UNINSTALLED,
                "com.vendor.old" to ChangeType.REMOVED,
                "com.vendor.ghost" to ChangeType.REMOVED,
                "com.vendor.newtrash" to ChangeType.INSTALLED,
                "com.vendor.newsilent" to ChangeType.ADDED_INACTIVE,
                "com.android.settings" to ChangeType.UNCHANGED,
                "com.android.systemui" to ChangeType.UNCHANGED,
            ),
        )
        assertThat(comparison.diff("com.android.settings")!!.versionChange).isEqualTo(VersionChange.UPGRADED)
        assertThat(comparison.diff("com.vendor.old")!!.label).isEqualTo("Old Thing")
        assertThat(comparison.changedCount).isEqualTo(8)
        assertThat(comparison.counts[ChangeType.UNCHANGED]).isEqualTo(4)
        assertThat(comparison.buildChanged).isTrue()
        assertThat(comparison.swapped).isFalse()
        // the most important changes come first
        assertThat(comparison.diffs.first().change).isEqualTo(ChangeType.INSTALLED)
        assertThat(comparison.diffs.last().change).isEqualTo(ChangeType.UNCHANGED)
    }

    @Test
    fun `nothing changed and the same build`() {
        val phone = phone()
        val device = id(phone)
        val a = snapshots.capture(device, 0, SnapshotKind.BEFORE_UPDATE, null)
        val b = snapshots.capture(device, 0, SnapshotKind.AFTER_UPDATE, null)

        val comparison = comparisons.compare(a.id, b.id)

        assertThat(comparison.changedCount).isZero()
        assertThat(comparison.buildChanged).isFalse()
    }

    @Test
    fun `snapshots picked in the wrong order are swapped`() {
        val phone = phone()
        val device = id(phone)
        val older = snapshots.capture(device, 0, SnapshotKind.OTHER, null)
        phone.set("com.android.chrome", PackageState.DISABLED)
        val newer = snapshots.capture(device, 0, SnapshotKind.OTHER, null)

        val comparison = comparisons.compare(newer.id, older.id)

        assertThat(comparison.swapped).isTrue()
        assertThat(comparison.before.id).isEqualTo(older.id)
        assertThat(comparison.diff("com.android.chrome")!!.change).isEqualTo(ChangeType.DISABLED)
    }

    @Test
    fun `refuses invalid pairs`() {
        val phoneA = phone("A1")
        val phoneB = phone("B2")
        phoneA.users += AndroidUser(10, "Work", true)
        val a0 = snapshots.capture(id(phoneA), 0, SnapshotKind.OTHER, null)
        val a10 = snapshots.capture(id(phoneA), 10, SnapshotKind.OTHER, null)
        val b0 = snapshots.capture(id(phoneB), 0, SnapshotKind.OTHER, null)

        assertThatThrownBy { comparisons.compare(a0.id, a0.id) }.isInstanceOf(InvalidComparisonException::class.java)
        assertThatThrownBy { comparisons.compare(a0.id, b0.id) }.isInstanceOf(InvalidComparisonException::class.java)
            .hasMessageContaining("different phones")
        assertThatThrownBy { comparisons.compare(a0.id, a10.id) }.isInstanceOf(InvalidComparisonException::class.java)
            .hasMessageContaining("different Android users")
        assertThatThrownBy { comparisons.compare(a0.id, 999) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `works without names and icons`() {
        val phone = phone()
        phone.helperWorks = false
        val device = id(phone)
        val a = snapshots.capture(device, 0, SnapshotKind.BEFORE_UPDATE, null)
        phone.add("com.vendor.newtrash", system = false)
        val b = snapshots.capture(device, 0, SnapshotKind.AFTER_UPDATE, null)

        val diff = comparisons.compare(a.id, b.id).diff("com.vendor.newtrash")!!

        assertThat(diff.change).isEqualTo(ChangeType.INSTALLED)
        assertThat(diff.label).isEqualTo("com.vendor.newtrash")
        assertThat(diff.iconHash).isNull()
    }
}
