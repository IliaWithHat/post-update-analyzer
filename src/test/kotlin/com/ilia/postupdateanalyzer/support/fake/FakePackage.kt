package com.ilia.postupdateanalyzer.support.fake

/** An app on a simulated phone. */
class FakePackage(
    val name: String,
    val system: Boolean,
    var versionCode: Long = 1,
    var versionName: String? = "1.0",
    var label: String? = null,
    var icon: ByteArray? = null,
)
