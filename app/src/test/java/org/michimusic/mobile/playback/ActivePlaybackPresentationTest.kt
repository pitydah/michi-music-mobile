package org.michimusic.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.player.PlayerState

class ActivePlaybackPresentationTest {

    private val localTrack = Track(
        id = "local_1",
        title = "Local Song",
        artist = "Local Artist",
        album = "Local Album",
        duration = 180000L,
        source = TrackSource.LOCAL,
    )

    private val remoteTrack = Track(
        id = "remote_1",
        title = "Remote Song",
        artist = "Remote Artist",
        album = "Remote Album",
        duration = 240000L,
        source = TrackSource.STREAMING,
    )

    private val remoteHostEndpoint = PlaybackEndpoint(
        id = "peer_mac_1",
        name = "Mac Studio",
        type = EndpointType.DESKTOP_PLAYER,
        isLocal = false,
        isConnected = true,
        capabilities = setOf("PLAYBACK", "REMOTE_CONTROL"),
    )

    private val streamReceiverEndpoint = PlaybackEndpoint(
        id = "peer_speaker_1",
        name = "Living Room Speaker",
        type = EndpointType.STREAM_RECEIVER,
        isLocal = false,
        isConnected = true,
        capabilities = setOf("AUDIO_OUTPUT"),
    )

    @Test
    fun `remote host with null track does NOT fall back to local track`() {
        val sessionState = PlaybackSessionState(
            activeEndpoint = remoteHostEndpoint,
            isRemoteActive = true,
            currentTrack = null,
            isPlaying = false,
            position = 0L,
            duration = 0L,
        )
        val localState = PlayerState(
            currentTrack = localTrack,
            isPlaying = true,
            position = 50000L,
            duration = 180000L,
        )

        val presentation = resolvePlaybackPresentation(sessionState, localState)

        assertFalse("MiniPlayer must not activate when remote host has no active track", presentation.hasActiveTrack)
        assertNull("Current track must not fall back to local track when remote host is active", presentation.currentTrack)
        assertEquals("Mac Studio", presentation.endpointDescription)
        assertFalse(presentation.isPlaying)
    }

    @Test
    fun `remote host with active track presents remote track and remote transport state`() {
        val sessionState = PlaybackSessionState(
            activeEndpoint = remoteHostEndpoint,
            isRemoteActive = true,
            currentTrack = remoteTrack,
            isPlaying = true,
            position = 45000L,
            duration = 240000L,
        )
        val localState = PlayerState(
            currentTrack = localTrack,
            isPlaying = false,
        )

        val presentation = resolvePlaybackPresentation(sessionState, localState)

        assertTrue(presentation.hasActiveTrack)
        assertEquals("remote_1", presentation.currentTrack?.id)
        assertEquals("Remote Song", presentation.currentTrack?.title)
        assertTrue(presentation.isPlaying)
        assertEquals(45000L, presentation.position)
        assertEquals(240000L, presentation.duration)
        assertEquals("Mac Studio", presentation.endpointDescription)
        assertTrue(presentation.isRemoteActive)
        assertFalse(presentation.isStreamReceiver)
    }

    @Test
    fun `local phone endpoint presents local player state`() {
        val sessionState = PlaybackSessionState(
            activeEndpoint = PlaybackEndpoint.LocalPhone,
            isRemoteActive = false,
            currentTrack = null,
            isPlaying = false,
        )
        val localState = PlayerState(
            currentTrack = localTrack,
            isPlaying = true,
            position = 12000L,
            duration = 180000L,
        )

        val presentation = resolvePlaybackPresentation(sessionState, localState)

        assertTrue(presentation.hasActiveTrack)
        assertEquals("local_1", presentation.currentTrack?.id)
        assertTrue(presentation.isPlaying)
        assertEquals(12000L, presentation.position)
        assertEquals("Este teléfono", presentation.endpointDescription)
        assertFalse(presentation.isRemoteActive)
        assertFalse(presentation.isStreamReceiver)
    }

    @Test
    fun `michi stream endpoint presents streaming track and receiver name`() {
        val sessionState = PlaybackSessionState(
            activeEndpoint = streamReceiverEndpoint,
            isRemoteActive = true,
            currentTrack = null,
            isPlaying = false,
        )
        val localState = PlayerState(
            currentTrack = localTrack,
            isPlaying = true,
            position = 30000L,
            duration = 180000L,
        )

        val presentation = resolvePlaybackPresentation(sessionState, localState)

        assertTrue(presentation.hasActiveTrack)
        assertEquals("local_1", presentation.currentTrack?.id)
        assertTrue(presentation.isPlaying)
        assertEquals(30000L, presentation.position)
        assertEquals("Living Room Speaker", presentation.endpointDescription)
        assertTrue(presentation.isRemoteActive)
        assertTrue(presentation.isStreamReceiver)
    }

    @Test
    fun `idle state presents inactive presentation`() {
        val sessionState = PlaybackSessionState(
            activeEndpoint = PlaybackEndpoint.LocalPhone,
            isRemoteActive = false,
            currentTrack = null,
            isPlaying = false,
        )
        val localState = PlayerState(
            currentTrack = null,
            isPlaying = false,
        )

        val presentation = resolvePlaybackPresentation(sessionState, localState)

        assertFalse(presentation.hasActiveTrack)
        assertNull(presentation.currentTrack)
        assertFalse(presentation.isPlaying)
    }
}
