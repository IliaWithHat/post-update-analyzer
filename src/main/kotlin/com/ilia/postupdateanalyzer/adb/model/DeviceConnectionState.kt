package com.ilia.postupdateanalyzer.adb.model

enum class DeviceConnectionState(val adbName: String, val hint: String?) {
    DEVICE("device", null),
    UNAUTHORIZED("unauthorized", "Unlock the phone and accept the \"Allow USB debugging\" prompt."),
    AUTHORIZING("authorizing", "Waiting for the phone to authorize this computer."),
    OFFLINE("offline", "The phone is not responding. Reconnect the cable or restart USB debugging."),
    NO_PERMISSIONS(
        "no permissions",
        "This computer is not allowed to talk to the phone. On Linux install the android udev rules " +
            "(package android-udev-rules / android-sdk-platform-tools-common) and add your user to the plugdev group.",
    ),
    RECOVERY("recovery", "The phone is in recovery mode. Reboot it into Android."),
    SIDELOAD("sideload", "The phone is in sideload mode. Reboot it into Android."),
    BOOTLOADER("bootloader", "The phone is in bootloader mode. Reboot it into Android."),
    CONNECTING("connecting", "Still connecting to the phone."),
    UNKNOWN("unknown", "ADB reports an unknown state for this phone."),
    ;

    companion object {
        fun of(text: String): DeviceConnectionState {
            val normalized = text.trim().lowercase()
            return entries.firstOrNull { it != UNKNOWN && normalized.startsWith(it.adbName) } ?: UNKNOWN
        }
    }
}
