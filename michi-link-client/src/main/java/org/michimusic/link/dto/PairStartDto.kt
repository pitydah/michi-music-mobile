package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PairStartRequestDto(
    val alias: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("client_device_id") val clientDeviceId: String,
    val capabilities: List<String> = emptyList(),
)

@Serializable
data class PairStartResponseDto(
    @SerialName("pairing_id") val pairingId: String = "",
    @SerialName("auth_methods") val authMethods: List<String> = emptyList(),
    @SerialName("server_alias") val serverAlias: String = "",
    @SerialName("auth_required") val authRequired: Boolean = true,
    @SerialName("server_device_id") val serverDeviceId: String = "",
)

@Serializable
data class PairConfirmRequestDto(
    @SerialName("pairing_id") val pairingId: String,
    val username: String = "",
    val password: String = "",
    val pin: String = "",
    @SerialName("client_device_id") val clientDeviceId: String,
    val alias: String = "",
    @SerialName("device_model") val deviceModel: String = "",
    val port: Int = 0,
    @SerialName("client_version") val clientVersion: String = "",
)

@Serializable
data class PairConfirmResponseDto(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_token") val deviceToken: String = "",
    @SerialName("session_token") val sessionToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    val permissions: List<String> = emptyList(),
    @SerialName("server_device_id") val serverDeviceId: String = "",
    @SerialName("server_alias") val serverAlias: String = "",
)

@Serializable
data class TokenRefreshRequestDto(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_device_id") val clientDeviceId: String,
)

@Serializable
data class TokenRefreshResponseDto(
    @SerialName("device_token") val deviceToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 3600L,
)
