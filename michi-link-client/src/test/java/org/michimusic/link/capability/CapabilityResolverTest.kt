package org.michimusic.link.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.dto.AudioCapabilitiesDto
import org.michimusic.link.dto.ServerInfoDto

class CapabilityResolverTest {

    @Test
    fun `null ServerInfo resolves to UNKNOWN capabilities`() {
        val resolved = CapabilityResolver.resolve(null)
        assertEquals(ResolvedCapabilities.UNKNOWN, resolved)
        assertFalse(resolved.isAudioReceiver)
        assertFalse(resolved.isMusicServer)
        assertFalse(resolved.isLibraryHost)
        assertFalse(resolved.isPlaybackHost)
        assertFalse(resolved.isSyncHost)
        assertFalse(resolved.isReceiverLite)
        assertNull(resolved.audioCapabilities)
        assertTrue(resolved.rawRoles.isEmpty())
        assertTrue(resolved.features.isEmpty())
    }

    @Test
    fun `receiver lite server info resolves strictly to audio receiver and receiver lite`() {
        val audioCaps = AudioCapabilitiesDto(
            transports = listOf("rtp_udp"),
            codecs = listOf("pcm_s16le"),
            sampleRates = listOf(48000, 44100),
            bitDepths = listOf(16),
            channels = listOf(2),
            packetMs = listOf(10),
            payloadTypes = listOf(97)
        )
        val info = ServerInfoDto(
            service = "michi-stream-dac",
            name = "Living Room ESP32",
            apiVersion = "v1-lite",
            michiLinkVersion = "1.0",
            roles = listOf("audio_receiver"),
            audio = audioCaps
        )

        val resolved = CapabilityResolver.resolve(info)
        assertTrue("Must be audio receiver", resolved.isAudioReceiver)
        assertTrue("Must be receiver lite", resolved.isReceiverLite)
        assertFalse("Must not be music server", resolved.isMusicServer)
        assertFalse("Must not be library host", resolved.isLibraryHost)
        assertNotNull(resolved.audioCapabilities)
        assertEquals(listOf("audio_receiver"), resolved.rawRoles)
    }

    @Test
    fun `desktop player server info resolves to playback host and library host`() {
        val info = ServerInfoDto(
            service = "michi-music-player",
            name = "Studio PC",
            apiVersion = "v1",
            michiLinkVersion = "1.0",
            roles = listOf("desktop_player", "library_master", "sync_host"),
        )

        val resolved = CapabilityResolver.resolve(info)
        assertTrue("Must be playback host", resolved.isPlaybackHost)
        assertTrue("Must be music server", resolved.isMusicServer)
        assertTrue("Must be library host", resolved.isLibraryHost)
        assertTrue("Must be sync host", resolved.isSyncHost)
        assertFalse("Must not be audio receiver", resolved.isAudioReceiver)
        assertFalse("Must not be receiver lite", resolved.isReceiverLite)
    }
}
