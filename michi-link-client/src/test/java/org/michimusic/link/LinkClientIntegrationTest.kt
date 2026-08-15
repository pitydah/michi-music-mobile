package org.michimusic.link

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.errors.LinkException

class LinkClientIntegrationTest {

    private val jsonConfig = Json { ignoreUnknownKeys = true }

    private fun createClient(engine: MockEngine): LinkClient {
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(jsonConfig)
            }
        }
        return LinkClient.createForTest(
            baseUrl = "http://test-server:53318",
            sessionToken = "test_session_token",
            deviceToken = "test_device_token",
            clientDeviceId = "test_device_id",
            httpClient = httpClient
        )
    }

    @Test
    fun `getServerInfo returns successfully parsed data`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = """
                    {
                        "server_device_id": "server_123",
                        "name": "Michi Server",
                        "service": "michimusic",
                        "version": "1.0.0",
                        "roles": ["music_server"],
                        "features": {
                            "token_refresh": true
                        },
                        "auth": {
                            "strategy": "LEGACY",
                            "token_refresh": true
                        }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = createClient(mockEngine)
        
        val result = client.getServerInfo()
        
        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals("server_123", info.serverDeviceId)
        assertEquals("Michi Server", info.name)
        assertEquals(true, client.tokenRefreshSupported)
    }

    @Test
    fun `getServerInfo handles unauthorized error correctly`() = runTest {
        val mockEngine = MockEngine { request ->
            respondError(HttpStatusCode.Unauthorized)
        }
        val client = createClient(mockEngine)
        
        val result = client.getServerInfo()
        println("getServerInfo unauthorized error: ${result.exceptionOrNull()}")
        assertFalse(result.isSuccess)
        val exception = result.exceptionOrNull()
        assertTrue(exception is LinkException.Unauthorized)
    }

    @Test
    fun `getPlaybackState parses successfully`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = """
                    {
                        "state": "playing",
                        "track_id": "track_1",
                        "position_ms": 15000,
                        "duration_ms": 180000,
                        "volume": 80
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = createClient(mockEngine)
        
        val result = client.getPlaybackState()
        
        assertTrue(result.isSuccess)
        val state = result.getOrThrow()
        assertEquals("playing", state.state)
        assertEquals("track_1", state.trackId)
        assertEquals(15000L, state.positionMs)
        assertEquals(80, state.volume)
    }

    @Test
    fun `pairStart parses correctly`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = """
                    {
                        "session_id": "session_abc",
                        "expires_at": "2024-01-01T00:00:00Z",
                        "attempts_remaining": 3,
                        "server_michi_id": "michi_123",
                        "server_public_key": "pub_key_base64"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = createClient(mockEngine)
        
        val result = client.pairStart(org.michimusic.link.dto.PairStartRequestDto(
            deviceName = "Android",
            deviceType = "mobile",
            roles = listOf(),
            authStrategy = "ED25519_CHALLENGE",
            michiId = "michi_456",
            publicKey = "client_pub_key",
            challengeNonce = "nonce",
            challengeSignature = "sig"
        ))
        
        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        println("PairStart Response: $response")
        assertEquals("session_abc", response.sessionId)
        assertEquals("michi_123", response.serverMichiId)
    }
}
