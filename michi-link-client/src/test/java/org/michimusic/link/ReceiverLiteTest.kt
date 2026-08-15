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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.dto.ReceiverSessionCreateRequest
import org.michimusic.link.dto.ReceiverSessionPatchRequest
import org.michimusic.link.errors.LinkException

class ReceiverLiteTest {

    @Test
    fun `createReceiverLiteSession executes POST and returns dynamic stream port`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:7331/api/v1/receiver-lite/session", request.url.toString())
            respond(
                content = """
                    {
                        "session_id": "550e8400-e29b-41d4-a716-446655440003",
                        "session_token": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                        "lease_seconds": 30,
                        "effective": {
                            "transport": "rtp_udp",
                            "codec": "pcm_s16le",
                            "sample_rate": 48000,
                            "bit_depth": 16,
                            "channels": 2,
                            "packet_ms": 10,
                            "buffer_ms": 120,
                            "payload_type": 97,
                            "ssrc": 12345,
                            "stream_port": 50004,
                            "volume": 70
                        }
                    }
                """.trimIndent(),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:7331",
            deviceToken = "test_tok",
            clientDeviceId = "mobile_1",
            httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        )

        val result = client.createReceiverLiteSession(ReceiverSessionCreateRequest())
        assertTrue(result.isSuccess)
        val resp = result.getOrThrow()
        assertEquals("550e8400-e29b-41d4-a716-446655440003", resp.sessionId)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", resp.sessionToken)
        assertEquals("Debe guardar session_token en LinkClient", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", client.currentReceiverSessionToken)
        assertEquals(30, resp.leaseSeconds)
        assertTrue("stream_port debe estar en rango dinámico 49152..65535", resp.effective.streamPort in 49152..65535)
        assertEquals(50004, resp.effective.streamPort)
    }

    @Test
    fun `sendReceiverLiteHeartbeat sends X-Michi-Session and canonical body`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("http://127.0.0.1:7331/api/v1/receiver-lite/heartbeat", request.url.toString())
            assertEquals("tok_123", request.headers["X-Michi-Session"])
            
            respond(
                content = """{"lease_seconds":30,"server_time":1700000000}""",
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
        client.currentReceiverSessionToken = "tok_123"

        val result = client.sendReceiverLiteHeartbeat(
            sessionId = "550e8400-e29b-41d4-a716-446655440003",
            sequence = 1L
        )
        assertTrue(result.isSuccess)
        assertEquals(30, result.getOrThrow().leaseSeconds)
    }

    @Test
    fun `patch and delete receiver-lite session send X-Michi-Session`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals("tok_abc", request.headers["X-Michi-Session"])
            when (request.method) {
                HttpMethod.Patch -> {
                    respond(
                        content = """{"session_id":"s1","state":"active","volume":80,"paused":false}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                HttpMethod.Delete -> {
                    respond(
                        content = "",
                        status = HttpStatusCode.NoContent,
                    )
                }
                else -> respond(content = "", status = HttpStatusCode.BadRequest)
            }
        }

        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:7331",
            httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        )
        client.currentReceiverSessionToken = "tok_abc"

        val patchResult = client.patchReceiverLiteSession(ReceiverSessionPatchRequest(volume = 80))
        assertTrue(patchResult.isSuccess)
        assertEquals(80, patchResult.getOrThrow().volume)

        val deleteResult = client.deleteReceiverLiteSession()
        assertTrue(deleteResult.isSuccess)
    }

    @Test
    fun `receiver-lite returns 401 on invalid session token and 409 on conflict`() = runTest {
        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath.contains("heartbeat")) {
                respond(
                    content = """{"error":"Unauthorized","message":"Invalid session token"}""",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                respond(
                    content = """{"error":"Conflict","message":"Sequence collision or session conflict"}""",
                    status = HttpStatusCode.Conflict,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        }

        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:7331",
            httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        )

        val hbResult = client.sendReceiverLiteHeartbeat(sessionId = "s1", sequence = 1L, sessionToken = "bad_tok")
        assertTrue(hbResult.isFailure)
        assertTrue("Error debe ser Unauthorized", hbResult.exceptionOrNull() is LinkException.Unauthorized)

        val createResult = client.createReceiverLiteSession()
        assertTrue(createResult.isFailure)
        assertTrue("Error debe ser SessionConflict", createResult.exceptionOrNull() is LinkException.SessionConflict)
    }
}
