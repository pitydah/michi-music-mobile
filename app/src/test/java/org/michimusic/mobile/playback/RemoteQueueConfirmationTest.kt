package org.michimusic.mobile.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.link.dto.PlaybackStateDto
import org.michimusic.link.dto.QueueTrackDto

class RemoteQueueConfirmationTest {

    private val track1 = Track(id = "srv_1", title = "Track 1", artist = "Artist", filepath = "http://srv/1", source = TrackSource.STREAMING)
    private val track2 = Track(id = "srv_2", title = "Track 2", artist = "Artist", filepath = "http://srv/2", source = TrackSource.STREAMING)
    private val track3 = Track(id = "srv_3", title = "Track 3", artist = "Artist", filepath = "http://srv/3", source = TrackSource.STREAMING)

    private val qTrack1 = QueueTrackDto(trackId = "srv_1", title = "Track 1", artist = "Artist")
    private val qTrack2 = QueueTrackDto(trackId = "srv_2", title = "Track 2", artist = "Artist")
    private val qTrack3 = QueueTrackDto(trackId = "srv_3", title = "Track 3", artist = "Artist")

    @Test
    fun verify_exactMatchAndPlaying_returnsConfirmed() {
        val requested = listOf(track1, track2)
        val remoteQueue = listOf(qTrack1, qTrack2)
        val state = PlaybackStateDto(playing = true, queueIndex = 0, trackId = "srv_1")

        val res = RemoteQueueConfirmation.verify(requested, 0, remoteQueue, state)

        assertTrue(res.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.CONFIRMED_PLAYING, res.status)
    }

    @Test
    fun verify_extraTracksInRemote_returnsMismatchCount() {
        // K47-01: Previous implementation accepted q.tracks.size >= tracks.size
        val requested = listOf(track1)
        val remoteQueue = listOf(qTrack1, qTrack2) // extra track!
        val state = PlaybackStateDto(playing = true, queueIndex = 0, trackId = "srv_1")

        val res = RemoteQueueConfirmation.verify(requested, 0, remoteQueue, state)

        assertFalse(res.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.MISMATCH_COUNT, res.status)
        assertTrue(res.note.contains("no coincide exactamente"))
    }

    @Test
    fun verify_missingTracksInRemote_returnsMismatchCount() {
        val requested = listOf(track1, track2)
        val remoteQueue = listOf(qTrack1) // missing track 2
        val state = PlaybackStateDto(playing = true, queueIndex = 0, trackId = "srv_1")

        val res = RemoteQueueConfirmation.verify(requested, 0, remoteQueue, state)

        assertFalse(res.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.MISMATCH_COUNT, res.status)
    }

    @Test
    fun verify_permutedTracksInRemote_returnsMismatchOrder() {
        val requested = listOf(track1, track2)
        val remoteQueue = listOf(qTrack2, qTrack1) // permuted!
        val state = PlaybackStateDto(playing = true, queueIndex = 1, trackId = "srv_1")

        val res = RemoteQueueConfirmation.verify(requested, 0, remoteQueue, state)

        assertFalse(res.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.MISMATCH_ORDER, res.status)
    }

    @Test
    fun verify_sameIndexDifferentTrackId_returnsMismatchActiveTrack() {
        // K47-01: Previous implementation accepted st.queueIndex == validIndex even if ID mismatched
        val requested = listOf(track1)
        val remoteQueue = listOf(QueueTrackDto(trackId = "srv_wrong", title = "Wrong"))
        val state = PlaybackStateDto(playing = true, queueIndex = 0, trackId = "srv_wrong")

        val res = RemoteQueueConfirmation.verify(requested, 0, remoteQueue, state)

        assertFalse(res.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.MISMATCH_ORDER, res.status)
    }

    @Test
    fun verify_remoteNotPlaying_returnsQueueSyncedNotConfirmed() {
        val requested = listOf(track1, track2)
        val remoteQueue = listOf(qTrack1, qTrack2)
        val state = PlaybackStateDto(playing = false, queueIndex = 0, trackId = "srv_1") // paused / buffering!

        val res = RemoteQueueConfirmation.verify(requested, 0, remoteQueue, state)

        assertFalse(res.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.QUEUE_SYNCED, res.status)
    }

    @Test
    fun verify_remoteIndexOutOfBounds_returnsIndexOutOfBounds() {
        val requested = listOf(track1)
        val remoteQueue = listOf(qTrack1)
        val state = PlaybackStateDto(playing = true, queueIndex = 5, trackId = "srv_1")

        val res = RemoteQueueConfirmation.verify(requested, 0, remoteQueue, state)

        assertFalse(res.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.INDEX_OUT_OF_BOUNDS, res.status)
    }

    @Test
    fun verify_telemetryNull_returnsTelemetryUnavailable() {
        val requested = listOf(track1)

        val res1 = RemoteQueueConfirmation.verify(requested, 0, null, PlaybackStateDto())
        assertFalse(res1.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.TELEMETRY_UNAVAILABLE, res1.status)

        val res2 = RemoteQueueConfirmation.verify(requested, 0, listOf(qTrack1), null)
        assertFalse(res2.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.TELEMETRY_UNAVAILABLE, res2.status)
    }

    @Test
    fun verify_remotePlaybackStateTrackIdMismatch_returnsMismatchActiveTrack() {
        // K48-01: remoteQueueTracks[0] has srv_1, but remotePlaybackState.trackId is srv_2 (host hasn't transitioned yet)
        val requested = listOf(track1, track2)
        val remoteQueue = listOf(qTrack1, qTrack2)
        val state = PlaybackStateDto(playing = true, queueIndex = 0, trackId = "srv_2")

        val res = RemoteQueueConfirmation.verify(requested, 0, remoteQueue, state)

        assertFalse(res.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.MISMATCH_ACTIVE_TRACK, res.status)
        assertTrue(res.note.contains("telemetría de reproducción"))
    }

    @Test
    fun verify_remotePlaybackStateTrackIdEmpty_returnsQueueSynced() {
        // K48-01: remote host did not publish trackId in telemetry
        val requested = listOf(track1, track2)
        val remoteQueue = listOf(qTrack1, qTrack2)
        val state = PlaybackStateDto(playing = true, queueIndex = 0, trackId = "")

        val res = RemoteQueueConfirmation.verify(requested, 0, remoteQueue, state)

        assertFalse(res.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.QUEUE_SYNCED, res.status)
        assertTrue(res.note.contains("no publicó el ID de pista activa"))
    }

    @Test
    fun verify_remotePlaybackStateQueueSizeMismatch_returnsMismatchCount() {
        val requested = listOf(track1, track2)
        val remoteQueue = listOf(qTrack1, qTrack2)
        val state = PlaybackStateDto(playing = true, queueIndex = 0, trackId = "srv_1", queueSize = 5)

        val res = RemoteQueueConfirmation.verify(requested, 0, remoteQueue, state)

        assertFalse(res.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.MISMATCH_COUNT, res.status)
        assertTrue(res.note.contains("Tamaño de cola en estado remoto"))
    }

    @Test
    fun verify_duplicateTrackIdsInQueue_distinguishesByIndex() {
        // Duplicate tracks in queue: srv_1, srv_2, srv_1
        val requested = listOf(track1, track2, track1)
        val remoteQueue = listOf(qTrack1, qTrack2, qTrack1)
        // Request index 2 (second occurrence of srv_1)
        // If remote state reports queueIndex = 0 (first occurrence), it must be rejected!
        val stateWrongIndex = PlaybackStateDto(playing = true, queueIndex = 0, trackId = "srv_1")
        val resWrongIndex = RemoteQueueConfirmation.verify(requested, 2, remoteQueue, stateWrongIndex)
        assertFalse(resWrongIndex.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.MISMATCH_ACTIVE_TRACK, resWrongIndex.status)

        // If remote state reports queueIndex = 2 and trackId = srv_1, it must be confirmed!
        val stateCorrectIndex = PlaybackStateDto(playing = true, queueIndex = 2, trackId = "srv_1")
        val resCorrect = RemoteQueueConfirmation.verify(requested, 2, remoteQueue, stateCorrectIndex)
        assertTrue(resCorrect.isFullyConfirmed)
        assertEquals(RemoteQueueSyncStatus.CONFIRMED_PLAYING, resCorrect.status)
    }
}
