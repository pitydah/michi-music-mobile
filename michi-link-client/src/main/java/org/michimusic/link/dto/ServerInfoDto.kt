package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

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
    val features: Map<String, JsonElement>? = null,
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
    val effectiveTokenRefresh: Boolean get() {
        if (auth?.tokenRefresh == true) return true
        val f = features ?: return false
        val tr = f["token_refresh"]
        return tr?.let { parseBool(it) } ?: false
    }
}

@Serializable
data class AuthInfoDto(
    val strategy: String = "",
    val required: Boolean = true,
    @SerialName("token_refresh") val tokenRefresh: Boolean = false,
    @SerialName("auth_methods") val authMethods: List<String> = emptyList(),
    @SerialName("requires_pairing") val requiresPairing: Boolean = true,
)

private fun parseBool(el: JsonElement): Boolean {
    return when (el) {
        is JsonPrimitive -> el.booleanOrNull ?: el.contentOrNull?.isNotEmpty() ?: true
        else -> true
    }
}
