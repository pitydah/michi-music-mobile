package org.michimusic.link

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkClientTest {
    private fun jsonClient(engine: MockEngine) = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    @Test
    fun fetchSyncManifest_usesNormalizedUrlAndAuthHeaders() = runBlocking {
        var requestedUrl = ""
        var authHeader = ""
        var deviceHeader = ""
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            authHeader = request.headers[HttpHeaders.Authorization].orEmpty()
            deviceHeader = request.headers["X-Michi-Device-Id"].orEmpty()
            respond(
                content = """{"device_id":"phone 1","tracks":[{"track_id":"t1","title":"Song"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://host:53318/",
            sessionToken = "session-token",
            deviceToken = "device-token",
            clientDeviceId = "phone-1",
            httpClient = jsonClient(engine),
        )

        val result = client.fetchSyncManifest("phone 1")

        assertTrue(result.isSuccess)
        assertEquals("http://host:53318/api/v1/sync/manifest?device_id=phone+1", requestedUrl)
        assertEquals("Bearer device-token", authHeader)
        assertEquals("phone-1", deviceHeader)
        assertEquals("t1", result.getOrThrow().tracks.single().trackId)
    }

    @Test
    fun getQueue_supportsLegacyItemsPayload() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"items":[{"track_id":"t1","title":"Legacy Track"}],"currentIndex":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = LinkClient.createForTest(
            baseUrl = "http://host:53318",
            deviceToken = "device-token",
            httpClient = jsonClient(engine),
        )

        val queue = client.getQueue().getOrThrow()

        assertEquals(0, queue.currentIndex)
        assertEquals("t1", queue.tracks.single().trackId)
        assertEquals("Legacy Track", queue.tracks.single().title)
    }
}
