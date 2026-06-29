package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerInfoDto(
    val server: String = "",
    @SerialName("server_alias") val serverAlias: String = "",
    val version: String = "1.0",
    @SerialName("requires_pairing") val requiresPairing: Boolean = true,
    @SerialName("auth_methods") val authMethods: List<String> = emptyList(),
    @SerialName("server_device_id") val serverDeviceId: String = "",
    val roles: List<String> = emptyList(),
    val features: List<String> = emptyList(),
)
