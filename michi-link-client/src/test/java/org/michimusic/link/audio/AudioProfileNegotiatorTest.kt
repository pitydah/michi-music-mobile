package org.michimusic.link.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.michimusic.link.dto.AudioCapabilitiesDto

class AudioProfileNegotiatorTest {

    @Test
    fun `negotiate falls back to standard 16_48 when capabilities is null`() {
        val req = AudioProfileNegotiator.negotiate(null)
        assertEquals("rtp_udp", req.transport)
        assertEquals("pcm_s16le", req.codec)
        assertEquals(48000, req.sampleRate)
        assertEquals(16, req.bitDepth)
        assertEquals(2, req.channels)
        assertEquals(10, req.packetMs)
        assertEquals(120, req.bufferMs)
        assertEquals(97, req.payloadType)
        assertEquals(70, req.volume)
    }

    @Test
    fun `negotiate selects Hi-Fi 24_96 profile when receiver supports it`() {
        val hifiCapabilities = AudioCapabilitiesDto(
            transports = listOf("rtp_udp"),
            codecs = listOf("pcm_s24le", "pcm_s16le"),
            sampleRates = listOf(96000, 48000, 44100),
            bitDepths = listOf(24, 16),
            channels = listOf(2),
            packetMs = listOf(10, 20),
            payloadTypes = listOf(97),
            bufferMsMin = 50,
            bufferMsMax = 200
        )

        val req = AudioProfileNegotiator.negotiate(hifiCapabilities, preferredVolume = 85)
        assertEquals("rtp_udp", req.transport)
        assertEquals("pcm_s24le", req.codec)
        assertEquals(96000, req.sampleRate)
        assertEquals(24, req.bitDepth)
        assertEquals(2, req.channels)
        assertEquals(10, req.packetMs)
        assertEquals(120, req.bufferMs)
        assertEquals(97, req.payloadType)
        assertEquals(85, req.volume)
    }

    @Test
    fun `negotiate selects Standard 16_48 profile for standard receiver`() {
        val standardCapabilities = AudioCapabilitiesDto(
            transports = listOf("rtp_udp"),
            codecs = listOf("pcm_s16le"),
            sampleRates = listOf(48000, 44100),
            bitDepths = listOf(16),
            channels = listOf(2),
            packetMs = listOf(20),
            payloadTypes = listOf(97)
        )

        val req = AudioProfileNegotiator.negotiate(standardCapabilities)
        assertEquals("rtp_udp", req.transport)
        assertEquals("pcm_s16le", req.codec)
        assertEquals(48000, req.sampleRate)
        assertEquals(16, req.bitDepth)
        assertEquals(2, req.channels)
        assertEquals(20, req.packetMs)
    }
}
