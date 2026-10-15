package com.ilia.postupdateanalyzer.snapshot.model

enum class SnapshotKind(val title: String) {
    BEFORE_UPDATE("Before update"),
    AFTER_UPDATE("After update"),
    OTHER("Other"),
}
