package org.michimusic.link

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.link.dto.PlaybackStateDto

/** MIM-08: lifecycle is authoritative; stopped is not silently rewritten to paused. */
class PlaybackStateTruthTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `stopped lifecycle wins over the legacy paused state string`() {
        val dto = json.decodeFromString<PlaybackStateDto>(
            """{"state":"paused","lifecycle":"stopped","playing":false,"position_ms":0}""",
        )
        assertEquals("stopped", dto.effectiveState)
    }

    @Test
    fun `ended and failed lifecycles keep their own state`() {
        assertEquals(
            "ended",
            json.decodeFromString<PlaybackStateDto>("""{"state":"paused","lifecycle":"ended"}""").effectiveState,
        )
        assertEquals(
            "error",
            json.decodeFromString<PlaybackStateDto>("""{"state":"paused","lifecycle":"failed"}""").effectiveState,
        )
    }

    @Test
    fun `playing and buffering are distinct`() {
        assertEquals(
            "playing",
            json.decodeFromString<PlaybackStateDto>("""{"lifecycle":"audio_flowing","playing":true}""").effectiveState,
        )
        assertEquals(
            "buffering",
            json.decodeFromString<PlaybackStateDto>("""{"lifecycle":"buffering"}""").effectiveState,
        )
    }

    @Test
    fun `legacy response without lifecycle still infers from playing`() {
        assertEquals(
            "playing",
            json.decodeFromString<PlaybackStateDto>("""{"playing":true}""").effectiveState,
        )
        assertEquals(
            "stopped",
            json.decodeFromString<PlaybackStateDto>("""{"playing":false}""").effectiveState,
        )
    }
}
