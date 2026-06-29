package org.michimusic.link.dto

data class ServerRegistry(
    val serverId: String,
    val name: String,
    val baseUrl: String,
    val roles: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    val deviceToken: String = "",
    val refreshToken: String = "",
    val lastSeen: Long = 0L,
    val trusted: Boolean = false,
)
