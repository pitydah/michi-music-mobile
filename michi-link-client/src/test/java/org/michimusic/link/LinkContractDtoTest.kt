package org.michimusic.link

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.dto.PairConfirmResponseDto
import org.michimusic.link.dto.PairStartResponseDto
import org.michimusic.link.dto.PlaybackStateDto
import org.michimusic.link.dto.QueueDto
import org.michimusic.link.dto.ServerInfoDto
import org.michimusic.link.dto.SyncManifestDto

class LinkContractDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun playbackStateDto_deserializesCanonicalPayload() {
        val payload = """
            {
                "state": "playing",
                "title": "Comfortably Numb",
                "artist": "Pink Floyd",
                "album": "The Wall",
                "position_ms": 142000,
                "duration_ms": 382000,
                "volume": 85,
                "shuffle": true,
                "repeat": "all"
            }
        """.trimIndent()

        val dto = json.decodeFromString<PlaybackStateDto>(payload)
        assertEquals("playing", dto.state)
        assertEquals("Comfortably Numb", dto.title)
        assertEquals("Pink Floyd", dto.artist)
        assertEquals("The Wall", dto.album)
        assertEquals(142000L, dto.positionMs)
        assertEquals(382000L, dto.durationMs)
        assertEquals(85, dto.volume)
        assertEquals(true, dto.shuffle)
        assertEquals("all", dto.repeat)
    }

    @Test
    fun queueDto_deserializesCanonicalTracks() {
        val payload = """
            {
                "queue_id": "q-99",
                "current_index": 2,
                "total_duration": 1200000,
                "tracks": [
                    {"track_id": "t1", "title": "Track 1", "artist": "Artist 1"},
                    {"track_id": "t2", "title": "Track 2", "artist": "Artist 2"},
                    {"track_id": "t3", "title": "Track 3", "artist": "Artist 3"}
                ]
            }
        """.trimIndent()

        val dto = json.decodeFromString<QueueDto>(payload)
        assertEquals("q-99", dto.queueId)
        assertEquals(2, dto.currentIndex)
        assertEquals(3, dto.tracks.size)
        assertEquals("t1", dto.tracks[0].trackId)
    }

    @Test
    fun pairStartAndConfirm_deserializeProperly() {
        val startPayload = """
            {
                "pairing_id": "pair-12345",
                "expires_at": "2026-08-14T23:59:59Z",
                "strategy": "pin"
            }
        """.trimIndent()

        val startDto = json.decodeFromString<PairStartResponseDto>(startPayload)
        assertEquals("pair-12345", startDto.pairingId)

        val confirmPayload = """
            {
                "device_token": "michi_token_secure_9988",
                "device_id": "phone-alpha"
            }
        """.trimIndent()

        val confirmDto = json.decodeFromString<PairConfirmResponseDto>(confirmPayload)
        assertEquals("michi_token_secure_9988", confirmDto.deviceToken)
        assertEquals("phone-alpha", confirmDto.deviceId)
    }

    @Test
    fun serverInfoDto_parsesRolesAndFeatures() {
        val payload = """
            {
                "name": "Michi Micro Server",
                "server_id": "server-01",
                "version": "1.4.0",
                "roles": ["server", "storage"]
            }
        """.trimIndent()

        val dto = json.decodeFromString<ServerInfoDto>(payload)
        assertEquals("Michi Micro Server", dto.effectiveName)
        assertEquals("server-01", dto.effectiveServerId)
        assertEquals("1.4.0", dto.version)
        assertTrue(dto.roles.contains("server"))
    }

    @Test
    fun syncManifestDto_parsesTracksAndPlaylists() {
        val payload = """
            {
                "device_id": "pixel-phone",
                "revision": 10,
                "tracks": [
                    {
                        "track_id": "track-flac-01",
                        "title": "Time",
                        "artist": "Pink Floyd",
                        "album": "The Dark Side of the Moon",
                        "format": "flac",
                        "duration_ms": 425000,
                        "file_size": 35000000
                    }
                ]
            }
        """.trimIndent()

        val dto = json.decodeFromString<SyncManifestDto>(payload)
        assertEquals("pixel-phone", dto.deviceId)
        assertEquals(1, dto.tracks.size)
        assertEquals("track-flac-01", dto.tracks[0].trackId)
        assertEquals("Time", dto.tracks[0].title)
    }

    @Test
    fun serverEvent_parsesSseEvent() {
        val payload = """
            {
                "type": "playback_changed",
                "timestamp": "2026-08-14T12:00:00Z",
                "payload": {
                    "state": "paused",
                    "position_ms": 95000
                }
            }
        """.trimIndent()

        val event = json.decodeFromString<ServerEvent>(payload)
        assertEquals("playback_changed", event.type)
        assertEquals("2026-08-14T12:00:00Z", event.timestamp)
        assertNotNull(event.payload)
    }
}
