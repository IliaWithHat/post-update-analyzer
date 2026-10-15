package com.ilia.postupdateanalyzer.metadata.model

enum class MetadataStatus(val title: String) {
    FULL("Names and icons loaded"),
    PARTIAL("Some names or icons are missing"),
    UNAVAILABLE("Names and icons could not be read"),
}
