package com.github.bumblebee202111.intellijcontextbridge.diagnostics

import kotlinx.serialization.Serializable

enum class DiagnosticStatus {
    PASS, FAIL, TIMEOUT
}

@Serializable
data class DiagnosticStep(
    val name: String,
    val status: DiagnosticStatus,
    val durationMs: Long,
    val error: String? = null
)

@Serializable
data class DiagnosticReport(
    val totalDurationMs: Long,
    val steps: List<DiagnosticStep>
)