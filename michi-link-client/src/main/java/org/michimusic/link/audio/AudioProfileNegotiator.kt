package org.michimusic.link.audio

import org.michimusic.link.dto.AudioCapabilitiesDto
import org.michimusic.link.dto.ReceiverSessionCreateRequest

/**
 * Negotiates an optimal audio streaming profile by intersecting the client's supported
 * capabilities with the remote receiver's advertised audio capabilities.
 */
object AudioProfileNegotiator {

    // Supported client capabilities matching current Android pipeline (PCM 16-bit verified)
    private val CLIENT_CODECS = listOf("pcm_s16le")
    private val CLIENT_SAMPLE_RATES = listOf(48000, 44100)
    private val CLIENT_BIT_DEPTHS = listOf(16)
    private val CLIENT_CHANNELS = listOf(2)
    private val CLIENT_PACKET_MS = listOf(10, 20)

    fun negotiate(
        capabilities: AudioCapabilitiesDto?,
        preferredVolume: Int = 70
    ): ReceiverSessionCreateRequest {
        if (capabilities == null) {
            // Default fallback profile (Standard 16/48)
            return ReceiverSessionCreateRequest(
                transport = "rtp_udp",
                codec = "pcm_s16le",
                sampleRate = 48000,
                bitDepth = 16,
                channels = 2,
                packetMs = 10,
                bufferMs = 120,
                payloadType = 97,
                volume = preferredVolume,
            )
        }

        val negotiatedTransport = capabilities.transports.firstOrNull { it.equals("rtp_udp", ignoreCase = true) } ?: "rtp_udp"
        
        // Negotiate bit depth & codec (strictly 16-bit for Phase 3)
        val negotiatedBitDepth = CLIENT_BIT_DEPTHS.firstOrNull { it in capabilities.bitDepths } ?: 16
        val negotiatedCodec = capabilities.codecs.firstOrNull { it.equals("pcm_s16le", ignoreCase = true) }
            ?: capabilities.codecs.firstOrNull { it.startsWith("pcm", ignoreCase = true) }
            ?: "pcm_s16le"

        // Negotiate sample rate (Standard 48000 / 44100 Hz)
        val negotiatedSampleRate = CLIENT_SAMPLE_RATES.firstOrNull { it in capabilities.sampleRates } ?: 48000

        // Negotiate channels
        val negotiatedChannels = CLIENT_CHANNELS.firstOrNull { it in capabilities.channels } ?: 2

        // Negotiate packet duration (ms)
        val negotiatedPacketMs = CLIENT_PACKET_MS.firstOrNull { it in capabilities.packetMs } ?: 10

        // Negotiate buffer duration (ms)
        var bufferMs = 120
        capabilities.bufferMsMin?.let { min -> bufferMs = bufferMs.coerceAtLeast(min) }
        capabilities.bufferMsMax?.let { max -> bufferMs = bufferMs.coerceAtMost(max) }

        // Negotiate payload type
        val negotiatedPayloadType = capabilities.payloadTypes.firstOrNull() ?: 97

        return ReceiverSessionCreateRequest(
            transport = negotiatedTransport,
            codec = negotiatedCodec,
            sampleRate = negotiatedSampleRate,
            bitDepth = negotiatedBitDepth,
            channels = negotiatedChannels,
            packetMs = negotiatedPacketMs,
            bufferMs = bufferMs,
            payloadType = negotiatedPayloadType,
            volume = preferredVolume,
        )
    }
}
