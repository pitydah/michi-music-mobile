package org.michimusic.link.capability

import org.michimusic.link.dto.AudioCapabilitiesDto
import org.michimusic.link.dto.ServerInfoDto

/**
 * Strongly-typed representation of verified remote node capabilities,
 * resolved exclusively from authenticated or queried ServerInfo.
 */
data class ResolvedCapabilities(
    val isAudioReceiver: Boolean,
    val isMusicServer: Boolean,
    val isLibraryHost: Boolean,
    val isPlaybackHost: Boolean,
    val isSyncHost: Boolean,
    val isReceiverLite: Boolean,
    val audioCapabilities: AudioCapabilitiesDto?,
    val rawRoles: List<String>,
    val features: List<String>,
) {
    companion object {
        val UNKNOWN = ResolvedCapabilities(
            isAudioReceiver = false,
            isMusicServer = false,
            isLibraryHost = false,
            isPlaybackHost = false,
            isSyncHost = false,
            isReceiverLite = false,
            audioCapabilities = null,
            rawRoles = emptyList(),
            features = emptyList(),
        )
    }
}

/**
 * Single source of truth for extracting typed capabilities from ServerInfo.
 * Does not grant functional roles based purely on multicast discovery announcements.
 */
object CapabilityResolver {
    fun resolve(serverInfo: ServerInfoDto?): ResolvedCapabilities {
        if (serverInfo == null) return ResolvedCapabilities.UNKNOWN

        val roles = serverInfo.roles.map { it.lowercase() }
        val features = serverInfo.effectiveFeatures
        val isReceiverLite = (serverInfo.apiVersion.equals("v1-lite", ignoreCase = true) ||
            serverInfo.michiLinkVersion.startsWith("1.") ||
            serverInfo.michiLinkVersion.startsWith("v1")) &&
            (roles.contains("audio_receiver") || serverInfo.audio != null)

        val isAudioReceiver = roles.contains("audio_receiver") || isReceiverLite
        val isMusicServer = roles.contains("music_server") || roles.contains("library_master")
        val isLibraryHost = roles.contains("library_host") || roles.contains("library_master") || features.contains("library")
        val isPlaybackHost = roles.contains("playback_host") || roles.contains("desktop_player")
        val isSyncHost = roles.contains("sync_host") || roles.contains("library_master")

        return ResolvedCapabilities(
            isAudioReceiver = isAudioReceiver,
            isMusicServer = isMusicServer,
            isLibraryHost = isLibraryHost,
            isPlaybackHost = isPlaybackHost,
            isSyncHost = isSyncHost,
            isReceiverLite = isReceiverLite,
            audioCapabilities = serverInfo.audio,
            rawRoles = serverInfo.roles,
            features = features,
        )
    }
}
