package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QrPairingRequest(
    @SerialName("device_name") val deviceName: String,
    @SerialName("client_device_id") val clientDeviceId: String,
)

@Serializable
data class QrPairingResponse(
    @SerialName("qr_code") val qrCode: String = "",
    @SerialName("qr_svg_url") val qrSvgUrl: String = "",
    val code: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
)

@Serializable
data class QrClaimRequest(
    @SerialName("client_device_id") val clientDeviceId: String,
)

@Serializable
data class QrClaimResponse(
    val success: Boolean = false,
    @SerialName("device_token") val deviceToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("device_id") val deviceId: String = "",
)

@Serializable
data class DeviceRevokeRequest(
    @SerialName("device_id") val deviceId: String,
)
