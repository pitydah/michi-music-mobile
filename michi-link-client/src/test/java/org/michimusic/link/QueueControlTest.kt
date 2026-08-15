package org.michimusic.link

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueControlTest {

    @Test
    fun `setQueueRepeatMode executes POST to queue repeat`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:7331/api/v1/queue/repeat", request.url.toString())
            respond(
                content = """{"repeat_mode":"one"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:7331",
            httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        )

        val result = client.setQueueRepeatMode("one")
        assertTrue(result.isSuccess)
        assertEquals("one", result.getOrThrow().repeatMode)
    }

    @Test
    fun `setQueueShuffle executes POST to queue shuffle`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:7331/api/v1/queue/shuffle", request.url.toString())
            respond(
                content = """{"shuffle_enabled":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:7331",
            httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        )

        val result = client.setQueueShuffle(true)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().shuffleEnabled)
    }
}
