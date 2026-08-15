package org.michimusic.link.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.core.models.PcmFormat
import org.michimusic.link.dto.AudioCapabilitiesDto

class AudioProfileNegotiatorTest {

    @Test
    fun `negotiate returns null when capabilities is null to enforce strict protocol conformance`() {
        val req = AudioProfileNegotiator.negotiate(null, sourceFormat = PcmFormat(sampleRate = 48000, bitDepth = 16, channels = 2))
        assertNull("Strict ReceiverLite negotiation must reject missing audio capabilities", req)
    }

    @Test
    fun `negotiate selects verified 16_48 profile for receiver supporting 16-bit`() {
        val capabilities = AudioCapabilitiesDto(
            transports = listOf("rtp_udp"),
            codecs = listOf("pcm_s16le"),
            sampleRates = listOf(48000, 44100),
            bitDepths = listOf(16),
            channels = listOf(2),
            packetMs = listOf(10, 20),
            payloadTypes = listOf(97),
            bufferMsMin = 50,
            bufferMsMax = 200
        )

        val req = AudioProfileNegotiator.negotiate(capabilities, sourceFormat = PcmFormat(sampleRate = 48000, bitDepth = 16, channels = 2), preferredVolume = 85)
        assertNotNull(req)
        assertEquals("rtp_udp", req!!.transport)
        assertEquals("pcm_s16le", req.codec)
        assertEquals(48000, req.sampleRate)
        assertEquals(16, req.bitDepth)
        assertEquals(2, req.channels)
        assertEquals(10, req.packetMs)
        assertEquals(120, req.bufferMs)
        assertEquals(97, req.payloadType)
        assertEquals(85, req.volume)
    }

    @Test
    fun `negotiate matches real 44_1kHz source format when receiver supports it`() {
        val capabilities = AudioCapabilitiesDto(
            transports = listOf("rtp_udp"),
            codecs = listOf("pcm_s16le"),
            sampleRates = listOf(48000, 44100),
            bitDepths = listOf(16),
            channels = listOf(2),
            packetMs = listOf(10, 20),
            payloadTypes = listOf(97)
        )

        val req = AudioProfileNegotiator.negotiate(capabilities, sourceFormat = PcmFormat(sampleRate = 44100, bitDepth = 16, channels = 2))
        assertNotNull(req)
        assertEquals(44100, req!!.sampleRate)
        assertEquals(16, req.bitDepth)
    }

    @Test
    fun `negotiate returns null when receiver only supports 24-bit and source is 16-bit`() {
        val incompatibleCapabilities = AudioCapabilitiesDto(
            transports = listOf("rtp_udp"),
            codecs = listOf("pcm_s24le"),
            sampleRates = listOf(96000),
            bitDepths = listOf(24),
            channels = listOf(2),
            packetMs = listOf(10),
            payloadTypes = listOf(97)
        )

        val req = AudioProfileNegotiator.negotiate(incompatibleCapabilities, sourceFormat = PcmFormat(sampleRate = 48000, bitDepth = 16, channels = 2))
        assertNull("Must return null on incompatible bit depth / sample rate / codec", req)
    }

    @Test
    fun `negotiate returns null when sample rate is not supported by receiver`() {
        val capabilities = AudioCapabilitiesDto(
            transports = listOf("rtp_udp"),
            codecs = listOf("pcm_s16le"),
            sampleRates = listOf(48000), // Receiver only supports 48000
            bitDepths = listOf(16),
            channels = listOf(2),
            packetMs = listOf(10),
            payloadTypes = listOf(97)
        )

        val req = AudioProfileNegotiator.negotiate(capabilities, sourceFormat = PcmFormat(sampleRate = 44100, bitDepth = 16, channels = 2))
        assertNull("Must return null when source is 44100Hz but receiver only supports 48000Hz", req)
    }

    @Test
    fun `negotiate returns null when transport rtp_udp is not supported`() {
        val capabilities = AudioCapabilitiesDto(
            transports = listOf("http_stream"),
            codecs = listOf("pcm_s16le"),
            sampleRates = listOf(48000),
            bitDepths = listOf(16),
            channels = listOf(2),
            packetMs = listOf(10),
            payloadTypes = listOf(97)
        )

        val req = AudioProfileNegotiator.negotiate(capabilities, sourceFormat = PcmFormat(sampleRate = 48000, bitDepth = 16, channels = 2))
        assertNull("Must return null when rtp_udp is not supported", req)
    }
}
