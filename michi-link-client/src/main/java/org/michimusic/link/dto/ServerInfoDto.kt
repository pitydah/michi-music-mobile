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
    @SerialName("michi_link_version") val michiLinkVersion: String = "",
    val version: String = "1.0",
    @SerialName("api_version") val apiVersion: String = "",
    val roles: List<String> = emptyList(),
    val features: ServerFeaturesDto? = null,
    val auth: AuthInfoDto? = null,
) {
    val effectiveServerId: String get() = serverId.ifEmpty { serverDeviceId.ifEmpty { server } }
    val effectiveName: String get() = name.ifEmpty { serverAlias.ifEmpty { server } }
    val effectiveAuthStrategy: PairingStrategy get() {
        val s = auth?.strategy ?: return PairingStrategy.LEGACY
        return when (s.uppercase()) {
            "PLAYER_PASSWORD", "PASSWORD" -> PairingStrategy.PLAYER_PASSWORD
            "SERVER_CODE", "CODE", "PIN" -> PairingStrategy.SERVER_CODE
            "RECEIVER_BUTTON" -> PairingStrategy.RECEIVER_BUTTON
            else -> PairingStrategy.LEGACY
        }
    }
}

@Serializable
data class AuthInfoDto(
    val strategy: String = "",
    @SerialName("auth_methods") val authMethods: List<String> = emptyList(),
    @SerialName("requires_pairing") val requiresPairing: Boolean = true,
)

@Serializable
data class ServerFeaturesDto(
    @SerialName("token_refresh") val tokenRefresh: Boolean = false,
    val streaming: Boolean = false,
    val sync: Boolean = false,
    val remote: Boolean = false,
    val events: Boolean = false,
)
