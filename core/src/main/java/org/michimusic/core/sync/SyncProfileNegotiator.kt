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
        // Strict honest negotiation:
        // Do NOT infer AAC from generic TRANSCODING or .m4a container.
        // Do NOT infer Opus from generic TRANSCODING or .ogg container.
        val hasAac = serverCapabilities.any {
            it.equals("TRANSCODING_AAC", ignoreCase = true) ||
            it.equals("CODEC_AAC", ignoreCase = true) ||
            it.equals("AAC", ignoreCase = true)
        } || serverRepresentations.any {
            it.equals("aac", ignoreCase = true) || it.contains("audio/aac", ignoreCase = true)
        }

        val hasOpus = serverCapabilities.any {
            it.equals("TRANSCODING_OPUS", ignoreCase = true) ||
            it.equals("CODEC_OPUS", ignoreCase = true) ||
            it.equals("OPUS", ignoreCase = true)
        } || serverRepresentations.any {
            it.equals("opus", ignoreCase = true) || it.contains("audio/opus", ignoreCase = true)
        }

        val defaultNoTranscodingReason = "El servidor no tiene transcodificación habilitada; solo se transferirá Calidad Original"

        val original = ProfileAvailability(
            profile = DeliveryProfile.ORIGINAL,
            isAvailable = true,
            reasonIfNot = null,
            codecDescription = "Formato original sin procesar",
        )

        val balanced = if (hasAac) {
            ProfileAvailability(
                profile = DeliveryProfile.BALANCED,
                isAvailable = true,
                reasonIfNot = null,
                codecDescription = "AAC 256 kbps (transcodificado por servidor)",
            )
        } else {
            val reason = if (serverRepresentations.isNotEmpty()) {
                "El servidor no ofrece representación AAC; solo se transferirá Calidad Original"
            } else {
                defaultNoTranscodingReason
            }
            ProfileAvailability(
                profile = DeliveryProfile.BALANCED,
                isAvailable = false,
                reasonIfNot = reason,
                codecDescription = "No soportado por el servidor actual",
            )
        }

        val lowStorage = if (hasOpus) {
            ProfileAvailability(
                profile = DeliveryProfile.LOW_STORAGE,
                isAvailable = true,
                reasonIfNot = null,
                codecDescription = "Opus 128 kbps (transcodificado por servidor)",
            )
        } else {
            val reason = if (serverRepresentations.isNotEmpty()) {
                "El servidor no ofrece representación Opus; solo se transferirá Calidad Original"
            } else {
                defaultNoTranscodingReason
            }
            ProfileAvailability(
                profile = DeliveryProfile.LOW_STORAGE,
                isAvailable = false,
                reasonIfNot = reason,
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
