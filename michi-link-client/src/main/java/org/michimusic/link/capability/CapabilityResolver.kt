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
 * Single source of truth for extracting typed capabilities from ServerInfo or stored device roles/features.
 * Does not grant functional roles based purely on multicast discovery announcements.
 */
object CapabilityResolver {
    fun resolve(
        roles: List<String>,
        features: List<String>,
        apiVersion: String = "",
        michiLinkVersion: String = "",
        audioCapabilities: AudioCapabilitiesDto? = null
    ): ResolvedCapabilities {
        if (roles.isEmpty() && features.isEmpty() && audioCapabilities == null) {
            return ResolvedCapabilities.UNKNOWN
        }

        val lowerRoles = roles.map { it.lowercase() }
        val isReceiverLite = (apiVersion.equals("v1-lite", ignoreCase = true) ||
            michiLinkVersion.startsWith("1.") ||
            michiLinkVersion.startsWith("v1")) &&
            (lowerRoles.contains("audio_receiver") || audioCapabilities != null)

        val isAudioReceiver = lowerRoles.contains("audio_receiver") || isReceiverLite || features.contains("audio_output") || features.contains("streaming")
        val isMusicServer = lowerRoles.contains("music_server") || lowerRoles.contains("library_master")
        val isLibraryHost = lowerRoles.contains("library_host") || lowerRoles.contains("library_master") || lowerRoles.contains("music_server") || features.contains("library")
        val isPlaybackHost = lowerRoles.contains("playback_host") || lowerRoles.contains("desktop_player") || features.contains("playback") || features.contains("remote_control")
        val isSyncHost = lowerRoles.contains("sync_host") || lowerRoles.contains("library_master") || features.contains("sync")

        return ResolvedCapabilities(
            isAudioReceiver = isAudioReceiver,
            isMusicServer = isMusicServer,
            isLibraryHost = isLibraryHost,
            isPlaybackHost = isPlaybackHost,
            isSyncHost = isSyncHost,
            isReceiverLite = isReceiverLite,
            audioCapabilities = audioCapabilities,
            rawRoles = roles,
            features = features,
        )
    }

    fun resolve(serverInfo: ServerInfoDto?): ResolvedCapabilities {
        if (serverInfo == null) return ResolvedCapabilities.UNKNOWN
        return resolve(
            roles = serverInfo.roles,
            features = serverInfo.effectiveFeatures,
            apiVersion = serverInfo.apiVersion,
            michiLinkVersion = serverInfo.michiLinkVersion,
            audioCapabilities = serverInfo.audio
        )
    }
}
