package org.michimusic.link.dto

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticReport(
    val device: String = "",
    val server: String = "",
    val baseUrl: String = "",
    val authStrategy: String = "",
    val tests: List<DiagnosticTest> = emptyList(),
    val errors: List<String> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class DiagnosticTest(
    val name: String,
    val passed: Boolean,
    val message: String = "",
    val durationMs: Long = 0L,
)
