package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LegacyPairStartResponseDto(
    @SerialName("pairing_id") val pairingId: String = "",
    @SerialName("auth_methods") val authMethods: List<String> = emptyList(),
    @SerialName("server_alias") val serverAlias: String = "",
    @SerialName("auth_required") val authRequired: Boolean = true,
    @SerialName("server_device_id") val serverDeviceId: String = "",
    val version: String = "",
)

@Serializable
data class LegacyPairConfirmResponseDto(
    val success: Boolean = true,
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_token") val deviceToken: String = "",
    @SerialName("session_token") val sessionToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    val permissions: List<String> = emptyList(),
    @SerialName("server_device_id") val serverDeviceId: String = "",
    @SerialName("server_alias") val serverAlias: String = "",
    val error: String = "",
)
