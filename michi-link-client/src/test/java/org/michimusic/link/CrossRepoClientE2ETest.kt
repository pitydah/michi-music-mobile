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
import org.michimusic.core.models.PlaybackTarget
import org.michimusic.core.models.ReceiverStatus
import org.michimusic.core.models.deriveStatus
import org.michimusic.link.dto.ReceiverDto
import org.michimusic.link.dto.ReceiverSessionCreateRequest
import org.michimusic.link.dto.ReceiverSessionPatchRequest
import org.michimusic.link.dto.RoomDto

class CrossRepoClientE2ETest {

    @Test
    fun `test Phase M1 to M3 - Receiver Device and Status Derivation`() {
        val dto = ReceiverDto(
            id = "rec_living_room",
            name = "Living Room",
            deviceType = "michi_stream_standard",
            volume = 70,
            isActive = true,
            sessionId = "sess_123",
            online = true,
            paired = true,
            compatible = true,
            isPlaying = true,
        )

        val device = dto.toReceiverDevice()
        assertEquals("rec_living_room", device.id)
        assertEquals("Living Room", device.name)
        assertEquals("michi_stream_standard", device.deviceType)
        assertEquals(listOf(48000), device.capabilities.sampleRates)
        assertEquals(listOf(16), device.capabilities.bitDepths)
        assertEquals(listOf("pcm_s16le"), device.capabilities.codecs)

        // Derivation tests
        assertEquals(ReceiverStatus.PLAYING, device.deriveStatus())

        val offlineDevice = device.copy(runtime = device.runtime.copy(online = false))
        assertEquals(ReceiverStatus.UNAVAILABLE, offlineDevice.deriveStatus())

        val unpairDevice = device.copy(runtime = device.runtime.copy(paired = false))
        assertEquals(ReceiverStatus.PAIRING_REQUIRED, unpairDevice.deriveStatus())

        val incompatibleDevice = device.copy(runtime = device.runtime.copy(compatible = false))
        assertEquals(ReceiverStatus.INCOMPATIBLE, incompatibleDevice.deriveStatus())

        assertEquals(ReceiverStatus.RECONNECTING, device.deriveStatus(isRecovering = true))
        assertEquals(ReceiverStatus.CONNECTING, device.deriveStatus(isConnecting = true))
    }

    @Test
    fun `test Phase M5 - PlaybackTarget Intent Modeling`() {
        val localTarget: PlaybackTarget = PlaybackTarget.LocalPhone
        val receiverTarget: PlaybackTarget = PlaybackTarget.Receiver("rec_1", "Living Room")
        val roomTarget: PlaybackTarget = PlaybackTarget.Room("room_all", "Whole Home")

        assertTrue(localTarget is PlaybackTarget.LocalPhone)
        assertEquals("rec_1", (receiverTarget as PlaybackTarget.Receiver).receiverId)
        assertEquals("room_all", (roomTarget as PlaybackTarget.Room).roomId)
    }

    @Test
    fun `test RECOVERY-01 - Mobile Disappears and Reconnects with Snapshot and Revision`() = runTest {
        var revisionRequested = 0
        val mockEngine = MockEngine { request ->
            if (request.url.encodedPath.contains("/api/v1/server/info")) {
                respond(
                    content = """
                        {
                            "name": "Michi Micro Server",
                            "version": "0.2.0",
                            "api_version": "v1",
                            "auth": {"required": true}
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else if (request.url.encodedPath.contains("/api/v1/playback/state")) {
                revisionRequested++
                respond(
                    content = """
                        {
                            "state": "playing",
                            "track_id": "track_42",
                            "title": "Song A",
                            "artist": "Artist B",
                            "position_ms": 45000,
                            "duration_ms": 200000,
                            "volume": 80,
                            "revision": 105
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        }

        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:9090",
            deviceToken = "tok_auth_1",
            httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        )

        // 1. Initial State
        val info = client.getServerInfo().getOrThrow()
        assertEquals("Michi Micro Server", info.name)

        // 2. Mobile Reconnect: Fetches canonical snapshot & revision
        val pbState = client.getPlaybackState().getOrThrow()
        assertEquals("playing", pbState.effectiveState)
        assertEquals("Song A", pbState.effectiveTitle)
        assertEquals(45000L, pbState.effectivePosition)
        assertTrue(revisionRequested >= 1)
    }

    @Test
    fun `test RECOVERY-02 - Stream Disappears and Reconnects without Re-pairing`() = runTest {
        val mockEngine = MockEngine { request ->
            if (request.method == HttpMethod.Get && request.url.encodedPath == "/api/v1/receivers") {
                respond(
                    content = """
                        [
                            {
                                "id": "rec_kitchen",
                                "name": "Kitchen Stream",
                                "device_type": "michi_stream_standard",
                                "online": true,
                                "paired": true,
                                "compatible": true,
                                "health": "HEALTHY",
                                "volume": 65
                            }
                        ]
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else if (request.method == HttpMethod.Post && request.url.encodedPath == "/api/v1/receiver-lite/session") {
                respond(
                    content = """
                        {
                            "session_id": "sess_recov_99",
                            "session_token": "tok_session_recovered_43_chars_long_12345",
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
                                "ssrc": 777,
                                "stream_port": 50020,
                                "volume": 65
                            }
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        }

        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:9090",
            deviceToken = "tok_auth_1",
            httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        )

        // Fetch receivers
        val receivers = client.fetchReceivers().getOrThrow()
        assertEquals(1, receivers.size)
        val dev = receivers[0].toReceiverDevice()
        assertEquals(ReceiverStatus.READY, dev.deriveStatus())

        // Recreate session without re-pairing
        val sessionResp = client.createReceiverLiteSession(ReceiverSessionCreateRequest()).getOrThrow()
        assertEquals("sess_recov_99", sessionResp.sessionId)
        assertEquals(50020, sessionResp.effective.streamPort)
        assertEquals(30, sessionResp.leaseSeconds)
    }

    @Test
    fun `test RECOVERY-03 - Micro Disappears and Session Teardown on Timeout`() = runTest {
        var deleteCalled = false
        val mockEngine = MockEngine { request ->
            if (request.method == HttpMethod.Delete && request.url.encodedPath == "/api/v1/receiver-lite/session") {
                deleteCalled = true
                respond("", HttpStatusCode.NoContent)
            } else {
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        }

        val client = LinkClient.createForTest(
            baseUrl = "http://127.0.0.1:9090",
            deviceToken = "tok_auth_1",
            httpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        )
        client.currentReceiverSessionToken = "active_session_token_123"

        // Teardown session
        val delRes = client.deleteReceiverLiteSession()
        assertTrue(delRes.isSuccess)
        assertTrue(deleteCalled)
        assertEquals(null, client.currentReceiverSessionToken)
    }
}
