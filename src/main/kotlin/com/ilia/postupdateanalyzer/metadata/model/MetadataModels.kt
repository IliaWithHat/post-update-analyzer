package com.ilia.postupdateanalyzer.metadata.model

data class HelperOutput(
    val apps: Map<String, AppMetadata>,
    /** Count announced by `PUA-BEGIN`, null if the line never came. */
    val announced: Int?,
    /** `PUA-END` was seen, so the output was not cut off. */
    val complete: Boolean,
    val fatal: String?,
    val badLines: Int,
)

data class MetadataResult(val apps: Map<String, AppMetadata>, val status: MetadataStatus, val message: String?)
