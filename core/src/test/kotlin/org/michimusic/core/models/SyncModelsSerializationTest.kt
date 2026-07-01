package org.michimusic.core.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncModelsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `register request serializes with snake_case`() {
        val request = RegisterRequest(
            alias = "TestPhone",
            device = "android",
            deviceModel = "Pixel 9",
            clientDeviceId = "abc-123",
        )
        val encoded = json.encodeToString(RegisterRequest.serializer(), request)
        assertTrue(encoded.contains("\"client_device_id\""))
        assertTrue(encoded.contains("\"device_model\""))
        assertFalse(encoded.contains("\"clientDeviceId\""))
    }

    @Test
    fun `announce message serializes with auth_required`() {
        val msg = AnnounceMessage(
            alias = "Server",
            device = "desktop",
            port = 53318,
            authRequired = true,
        )
        val encoded = json.encodeToString(AnnounceMessage.serializer(), msg)
        assertTrue(encoded.contains("\"auth_required\""))
    }

    @Test
    fun `pair start request serializes with snake_case`() {
        val request = PairStartRequest(
            username = "admin",
            password = "secret",
            deviceAlias = "AndroidPhone",
            deviceType = "android",
            clientDeviceId = "xyz-789",
        )
        val encoded = json.encodeToString(PairStartRequest.serializer(), request)
        assertTrue(encoded.contains("\"device_alias\""))
        assertTrue(encoded.contains("\"client_device_id\""))
        assertTrue(encoded.contains("\"username\""))
    }

    @Test
    fun `search response can parse KDE format`() {
        val input = """{"results": [{"id": "1", "title": "Song", "artist": "Artist"}], "query": "song"}"""
        val response = json.decodeFromString<SearchResponse>(input)
        assertEquals(1, response.results.size)
        assertEquals("Song", response.results[0].title)
        assertEquals("song", response.query)
    }

    @Test
    fun `track with defaults deserializes`() {
        val input = """{"id": "local_42", "title": "Test"}"""
        val track = json.decodeFromString<Track>(input)
        assertEquals("local_42", track.id)
        assertEquals("Test", track.title)
        assertEquals("", track.artist)
        assertEquals(TrackSource.LOCAL, track.source)
    }
}