package org.michimusic.core.sync

import org.michimusic.core.models.DeliveryChoice
import org.michimusic.core.models.DeliveryProfile
import org.michimusic.core.models.ProfileAvailability

object SyncProfileNegotiator {

    /**
     * Inspects server capabilities and available representations to determine which delivery
     * profiles are genuinely available from the server.
     *
     * Rule: If the server does NOT advertise transcoding or alternate representations,
     * BALANCED and LOW_STORAGE are disabled with an honest user-facing reason.
     * We never perform fake transcoding or pretend to compress audio locally.
     */
    fun evaluateAvailableProfiles(
        serverCapabilities: Set<String>,
        serverRepresentations: List<String> = emptyList(),
    ): List<ProfileAvailability> {
        val hasTranscoding = serverCapabilities.any {
            it.equals("TRANSCODING", ignoreCase = true) ||
            it.equals("MULTI_PROFILE", ignoreCase = true)
        } || serverRepresentations.any {
            it.contains("aac", ignoreCase = true) ||
            it.contains("opus", ignoreCase = true) ||
            it.contains("mp3", ignoreCase = true)
        }

        val original = ProfileAvailability(
            profile = DeliveryProfile.ORIGINAL,
            isAvailable = true,
            reasonIfNot = null,
            codecDescription = "Formato original sin procesar",
        )

        val balanced = if (hasTranscoding) {
            ProfileAvailability(
                profile = DeliveryProfile.BALANCED,
                isAvailable = true,
                reasonIfNot = null,
                codecDescription = "AAC 256 kbps (transcodificado por servidor)",
            )
        } else {
            ProfileAvailability(
                profile = DeliveryProfile.BALANCED,
                isAvailable = false,
                reasonIfNot = "El servidor no tiene transcodificación habilitada; solo se transferirá Calidad Original",
                codecDescription = "No soportado por el servidor actual",
            )
        }

        val lowStorage = if (hasTranscoding) {
            ProfileAvailability(
                profile = DeliveryProfile.LOW_STORAGE,
                isAvailable = true,
                reasonIfNot = null,
                codecDescription = "Opus 128 kbps (transcodificado por servidor)",
            )
        } else {
            ProfileAvailability(
                profile = DeliveryProfile.LOW_STORAGE,
                isAvailable = false,
                reasonIfNot = "El servidor no tiene transcodificación habilitada; solo se transferirá Calidad Original",
                codecDescription = "No soportado por el servidor actual",
            )
        }

        return listOf(original, balanced, lowStorage)
    }

    /**
     * Resolves the effective DeliveryChoice. If the requested profile is not supported,
     * falls back safely to ORIGINAL.
     */
    fun resolveDeliveryChoice(
        requestedProfile: DeliveryProfile,
        serverCapabilities: Set<String>,
        serverRepresentations: List<String> = emptyList(),
    ): DeliveryChoice {
        val availabilities = evaluateAvailableProfiles(serverCapabilities, serverRepresentations)
        val selected = availabilities.firstOrNull { it.profile == requestedProfile && it.isAvailable }

        return when (selected?.profile) {
            DeliveryProfile.BALANCED -> DeliveryChoice(
                profile = DeliveryProfile.BALANCED,
                codec = "aac",
                maxBitrateKbps = 256,
                isServerTranscoded = true,
            )
            DeliveryProfile.LOW_STORAGE -> DeliveryChoice(
                profile = DeliveryProfile.LOW_STORAGE,
                codec = "opus",
                maxBitrateKbps = 128,
                isServerTranscoded = true,
            )
            else -> DeliveryChoice(
                profile = DeliveryProfile.ORIGINAL,
                codec = "original",
                maxBitrateKbps = null,
                isServerTranscoded = false,
            )
        }
    }
}
