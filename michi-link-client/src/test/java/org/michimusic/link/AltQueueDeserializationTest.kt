package org.michimusic.link

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.link.dto.QueueDto

class AltQueueDeserializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun deserializeMicroServerPayload_preservesSnakeCaseCurrentIndexAndQueueId() {
        val rawJson = """
            {
                "items": [
                    {
                        "track_id": "srv_track_1",
                        "title": "Cosmic Drift",
                        "artist": "Aero Synthetics",
                        "duration": 180000
                    },
                    {
                        "track_id": "srv_track_2",
                        "title": "Subatomic Pulse",
                        "artist": "Aero Synthetics",
                        "duration": 210000
                    }
                ],
                "current_index": 1,
                "queue_id": "micro_queue_98765",
                "total_duration": 390000
            }
        """.trimIndent()

        val alt = json.decodeFromString<AltQueueDto>(rawJson)
        val queueDto = QueueDto(
            tracks = alt.items,
            currentIndex = alt.effectiveIndex,
            queueId = alt.effectiveQueueId,
            totalDuration = alt.effectiveTotalDuration
        )

        assertEquals(2, queueDto.tracks.size)
        assertEquals("srv_track_1", queueDto.tracks[0].trackId)
        assertEquals("srv_track_2", queueDto.tracks[1].trackId)
        assertEquals("current_index must map to 1 (K52-02)", 1, queueDto.currentIndex)
        assertEquals("queue_id must map to micro_queue_98765 (K52-02)", "micro_queue_98765", queueDto.queueId)
        assertEquals(390000L, queueDto.totalDuration)
    }

    @Test
    fun deserializeCamelCasePayload_mapsFallbackSuccessfully() {
        val rawJson = """
            {
                "items": [
                    {
                        "track_id": "srv_track_1",
                        "title": "Cosmic Drift",
                        "artist": "Aero Synthetics",
                        "duration": 180000
                    }
                ],
                "currentIndex": 0,
                "queueId": "camel_queue_123",
                "totalDuration": 180000
            }
        """.trimIndent()

        val alt = json.decodeFromString<AltQueueDto>(rawJson)
        val queueDto = QueueDto(
            tracks = alt.items,
            currentIndex = alt.effectiveIndex,
            queueId = alt.effectiveQueueId,
            totalDuration = alt.effectiveTotalDuration
        )

        assertEquals(1, queueDto.tracks.size)
        assertEquals(0, queueDto.currentIndex)
        assertEquals("camel_queue_123", queueDto.queueId)
        assertEquals(180000L, queueDto.totalDuration)
    }
}
