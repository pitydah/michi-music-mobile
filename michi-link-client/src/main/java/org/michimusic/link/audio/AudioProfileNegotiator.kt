package org.michimusic.link.audio

import org.michimusic.core.models.PcmFormat
import org.michimusic.link.dto.AudioCapabilitiesDto
import org.michimusic.link.dto.ReceiverSessionCreateRequest

/**
 * Negotiates an optimal audio streaming profile by strictly intersecting the client's
 * real decoded PCM format ([PcmFormat]) with the remote receiver's advertised audio capabilities.
 *
 * Returns null if capabilities are missing (no fictional fallback) or if no compatible
 * profile can be strictly matched.
 */
object AudioProfileNegotiator {

    private val SUPPORTED_PACKET_MS = listOf(10, 20)

    fun negotiate(
        capabilities: AudioCapabilitiesDto?,
        sourceFormat: PcmFormat,
        preferredVolume: Int = 70,
    ): ReceiverSessionCreateRequest? {
        if (capabilities == null) {
            // Strict ReceiverLite protocol: capabilities must be explicitly advertised
            return null
        }

        // 1. Strict transport check
        val supportsRtpUdp = capabilities.transports.any { it.equals("rtp_udp", ignoreCase = true) }
        if (!supportsRtpUdp) return null

        // 2. Strict codec check
        val matchingCodec = capabilities.codecs.firstOrNull { it.equals(sourceFormat.codec, ignoreCase = true) }
            ?: return null

        // 3. Strict bit depth check
        if (sourceFormat.bitDepth !in capabilities.bitDepths) return null

        // 4. Strict sample rate check
        if (sourceFormat.sampleRate !in capabilities.sampleRates) return null

        // 5. Strict channels check
        if (sourceFormat.channels !in capabilities.channels) return null

        // 6. Packet duration
        val negotiatedPacketMs = SUPPORTED_PACKET_MS.firstOrNull { it in capabilities.packetMs }
            ?: capabilities.packetMs.firstOrNull()
            ?: 10

        // 7. Buffer duration
        var bufferMs = 120
        capabilities.bufferMsMin?.let { min -> bufferMs = bufferMs.coerceAtLeast(min) }
        capabilities.bufferMsMax?.let { max -> bufferMs = bufferMs.coerceAtMost(max) }

        // 8. Payload type
        val negotiatedPayloadType = capabilities.payloadTypes.firstOrNull() ?: 97

        return ReceiverSessionCreateRequest(
            transport = "rtp_udp",
            codec = matchingCodec,
            sampleRate = sourceFormat.sampleRate,
            bitDepth = sourceFormat.bitDepth,
            channels = sourceFormat.channels,
            packetMs = negotiatedPacketMs,
            bufferMs = bufferMs,
            payloadType = negotiatedPayloadType,
            volume = preferredVolume,
        )
    }
}
