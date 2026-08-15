package org.michimusic.link.audio

import org.michimusic.link.dto.AudioCapabilitiesDto
import org.michimusic.link.dto.ReceiverSessionCreateRequest

data class SourcePcmFormat(
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val bitDepth: Int = 16,
    val codec: String = "pcm_s16le",
)

/**
 * Negotiates an optimal audio streaming profile by strictly intersecting the client's
 * real decoded PCM format with the remote receiver's advertised audio capabilities.
 *
 * Returns null if no compatible profile can be strictly matched.
 */
object AudioProfileNegotiator {

    private val SUPPORTED_PACKET_MS = listOf(10, 20)

    fun negotiate(
        capabilities: AudioCapabilitiesDto?,
        sourceFormat: SourcePcmFormat = SourcePcmFormat(),
        preferredVolume: Int = 70,
    ): ReceiverSessionCreateRequest? {
        if (capabilities == null) {
            // Null capabilities fallback: only allow if source format is verified 16-bit stereo PCM (48kHz or 44.1kHz)
            if (sourceFormat.bitDepth != 16 || sourceFormat.channels != 2) return null
            if (sourceFormat.sampleRate != 48000 && sourceFormat.sampleRate != 44100) return null
            return ReceiverSessionCreateRequest(
                transport = "rtp_udp",
                codec = sourceFormat.codec,
                sampleRate = sourceFormat.sampleRate,
                bitDepth = sourceFormat.bitDepth,
                channels = sourceFormat.channels,
                packetMs = 10,
                bufferMs = 120,
                payloadType = 97,
                volume = preferredVolume,
            )
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
