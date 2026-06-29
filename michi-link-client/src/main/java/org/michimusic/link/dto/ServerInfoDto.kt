package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerInfoDto(
    val service: String = "",
    val name: String = "",
    @SerialName("server_id") val serverId: String = "",
    val server: String = "",
    @SerialName("server_alias") val serverAlias: String = "",
    @SerialName("server_device_id") val serverDeviceId: String = "",
) {
    val effectiveServerId: String get() = serverId.ifEmpty { serverDeviceId.ifEmpty { server } }
    val effectiveName: String get() = name.ifEmpty { serverAlias.ifEmpty { server } }
}

@Serializable
data class ServerVersionDto(
    val version: String = "1.0",
    @SerialName("api_version") val apiVersion: String = "",
    @SerialName("requires_pairing") val requiresPairing: Boolean = true,
    @SerialName("auth_methods") val authMethods: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val features: ServerFeaturesDto? = null,
)

@Serializable
data class ServerFeaturesDto(
    @SerialName("token_refresh") val tokenRefresh: Boolean = false,
    val streaming: Boolean = false,
    val sync: Boolean = false,
    val remote: Boolean = false,
    val events: Boolean = false,
)
