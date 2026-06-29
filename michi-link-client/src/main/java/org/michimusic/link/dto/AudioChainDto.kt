package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AudioChainDto(
    @SerialName("source_id") val sourceId: String = "",
    @SerialName("source_name") val sourceName: String = "",
    @SerialName("output_id") val outputId: String = "",
    @SerialName("output_name") val outputName: String = "",
    @SerialName("output_type") val outputType: String = "",
    val zones: List<ZoneDto> = emptyList(),
)

@Serializable
data class ZoneDto(
    val id: String = "",
    val name: String = "",
    val active: Boolean = false,
)

@Serializable
data class ErrorDto(
    val code: String = "",
    val message: String = "",
    val details: String = "",
)
