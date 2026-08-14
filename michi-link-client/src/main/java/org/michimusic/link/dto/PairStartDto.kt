package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PairStartRequestDto(
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_type") val deviceType: String,
    val roles: List<String>,
    @SerialName("auth_strategy") val authStrategy: String,
    @SerialName("michi_id") val michiId: String,
    @SerialName("public_key") val publicKey: String,
    @SerialName("challenge_nonce") val challengeNonce: String,
    @SerialName("challenge_signature") val challengeSignature: String,
)

@Serializable
data class PairStartResponseDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("attempts_remaining") val attemptsRemaining: Int,
    @SerialName("server_michi_id") val serverMichiId: String,
    @SerialName("server_public_key") val serverPublicKey: String,
)

@Serializable
data class PairConfirmRequestDto(
    @SerialName("session_id") val sessionId: String,
    val pin: String,
    @SerialName("michi_id") val michiId: String,
    @SerialName("public_key") val publicKey: String,
)

@Serializable
data class PairConfirmResponseDto(
    val token: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
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
