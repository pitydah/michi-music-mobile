package org.michimusic.link

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.errors.LinkException
import org.michimusic.link.home.HomeDeviceAuthChallengeRequestDto
import org.michimusic.link.home.HomeEnrollmentChallengeRequestDto

class HomeQueueContractTest {

    private fun client(
        baseUrl: String = "https://127.0.0.1:443",
        pinned: Boolean = true,
        engine: MockEngine,
    ): LinkClient = LinkClient.createForTest(
        baseUrl = baseUrl,
        clientDeviceId = "rz2gnw8LRcZPMln7OHAknoEL5Dg8TFyJ7utfFKJyPlE",
        httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        },
        assumePinnedAirCatForTest = pinned,
    )

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun `R1_4 queue exposes item identity version and degraded truth`() = runTest {
        val engine = MockEngine {
            respond(
                """
                {"queue_id":"q-1","queue_version":7,"queue_degraded":false,
                 "queue_degraded_reason":null,"items_count":3,
                 "items":[{"id":"item-a","track_id":"track-1","position":0},
                          {"id":"item-b","track_id":"track-1","position":1},
                          {"id":"item-c","track_id":"track-3","position":2}],
                 "current_track_id":"track-1","current_index":0,"position_ms":1200,
                 "playing":true,"volume":55}
                """.trimIndent(),
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val queue = client(engine = engine).getQueue().getOrThrow()
        assertEquals(7L, queue.queueVersion)
        assertFalse(queue.queueDegraded)
        assertEquals(listOf("item-a", "item-b", "item-c"), queue.itemIds)
        assertEquals(listOf("track-1", "track-1", "track-3"), queue.tracks.map { it.trackId })
        assertTrue(queue.hasStableItemIds())
        assertEquals("item-b", queue.itemIdAt(1))
        assertEquals("track-1", queue.effectiveCurrentTrackId)
        assertEquals(1200L, queue.positionMs)
        assertTrue(queue.playing)
    }

    @Test
    fun `queue degraded refusal is a typed error not generic HTTP failure`() = runTest {
        val engine = MockEngine {
            respond(
                """{"error":{"code":"QUEUE_DEGRADED","message":"coordinator rolled back","details":{}}}""",
                HttpStatusCode.ServiceUnavailable, jsonHeaders(),
            )
        }
        val result = client(engine = engine).getQueue()
        val error = result.exceptionOrNull()
        assertTrue("expected QueueDegraded but was $error", error is LinkException.QueueDegraded)
        assertEquals("coordinator rolled back", error?.message?.substringAfter(": "))
    }

    @Test
    fun `member revoked is distinct from plain forbidden`() = runTest {
        val revoked = MockEngine {
            respond(
                """{"error":{"code":"MEMBER_REVOKED","message":"member is revoked","details":{}}}""",
                HttpStatusCode.Forbidden, jsonHeaders(),
            )
        }
        assertTrue(
            client(engine = revoked).getQueue().exceptionOrNull() is LinkException.MemberRevoked,
        )
        val forbidden = MockEngine {
            respond(
                """{"error":{"code":"FORBIDDEN","message":"no","details":{}}}""",
                HttpStatusCode.Forbidden, jsonHeaders(),
            )
        }
        assertTrue(
            client(engine = forbidden).getQueue().exceptionOrNull() is LinkException.Forbidden,
        )
    }

    @Test
    fun `jump resolves queue item id against the current version and posts only index`() = runTest {
        var postedBody = ""
        var getCount = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                getCount++
                respond(
                    """
                    {"queue_id":"q-1","queue_version":7,"items":[
                      {"id":"item-a","track_id":"t1","position":0},
                      {"id":"item-b","track_id":"t1","position":1}
                    ],"current_index":0}
                    """.trimIndent(),
                    HttpStatusCode.OK, jsonHeaders(),
                )
            } else {
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/api/v1/queue/jump", request.url.encodedPath)
                postedBody = request.body.toByteArray().decodeToString()
                respond("""{"status":"ok","queue_version":8}""", HttpStatusCode.OK, jsonHeaders())
            }
        }
        val result = client(engine = engine).jumpQueueToItem("item-b", expectedQueueVersion = 7)
        assertTrue(result.isSuccess)
        assertEquals(1, getCount)
        assertTrue(postedBody.contains("\"index\":1"))
        assertTrue(postedBody.contains("\"queue_id\":\"q-1\""))
    }

    @Test
    fun `jump fails closed when the observed version is stale`() = runTest {
        var posts = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) posts++
            respond(
                """{"queue_id":"q-1","queue_version":9,"items":[{"id":"item-a","track_id":"t1","position":0}],"current_index":0}""",
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val result = client(engine = engine).jumpQueueToItem("item-a", expectedQueueVersion = 7)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is LinkException.SessionConflict)
        assertEquals(0, posts)
    }

    @Test
    fun `play command carries track_id and never the legacy play_track verb`() = runTest {
        var body = ""
        val engine = MockEngine { request ->
            body = request.body.toByteArray().decodeToString()
            respond("""{"status":"accepted"}""", HttpStatusCode.OK, jsonHeaders())
        }
        val result = client(engine = engine).sendPlaybackCommand("play", trackId = "track-9")
        assertTrue(result.isSuccess)
        assertTrue(body.contains("\"command\":\"play\""))
        assertTrue(body.contains("\"track_id\":\"track-9\""))
        assertFalse(body.contains("play_track"))
        assertFalse("empty legacy value must be omitted: $body", body.contains("\"value\""))
    }

    @Test
    fun `addQueueItems preserves intentional duplicate track occurrences`() = runTest {
        var body = ""
        val engine = MockEngine { request ->
            body = request.body.toByteArray().decodeToString()
            respond("""{"status":"ok","added":2}""", HttpStatusCode.OK, jsonHeaders())
        }
        assertTrue(client(engine = engine).addQueueItems(listOf("t1", "t1")).isSuccess)
        assertTrue(body.contains("\"track_ids\":[\"t1\",\"t1\"]"))
    }

    @Test
    fun `set_volume sends the canonical integer without a conflicting empty value`() = runTest {
        var body = ""
        val engine = MockEngine { request ->
            body = request.body.toByteArray().decodeToString()
            respond("""{"status":"accepted","volume":75}""", HttpStatusCode.OK, jsonHeaders())
        }
        assertTrue(client(engine = engine).sendSetVolume(75).isSuccess)
        assertTrue(body.contains("\"command\":\"set_volume\""))
        assertTrue(body.contains("\"volume\":75"))
        assertFalse(body.contains("\"value\""))
    }

    @Test
    fun `out of range volume fails locally without inventing a clamped value`() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond("""{"status":"accepted"}""", HttpStatusCode.OK, jsonHeaders())
        }
        val result = client(engine = engine).sendSetVolume(101)
        assertTrue(result.isFailure)
        assertEquals(0, requests)
    }

    @Test
    fun `server info with incoherent canonical identity is refused`() = runTest {
        val engine = MockEngine {
            respond(
                """{"service":"michi-micro-server","michi_id":"${"A".repeat(43)}",
                    "public_key":"F8t5-ytBIPKx7GXkGY1uCLKOgT_rAeSkAIObheGAgM4",
                    "identity_scheme":"ed25519-blake3-v1"}""",
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val error = client(engine = engine).getServerInfo().exceptionOrNull()
        assertTrue(error is LinkException.ServerError)
        assertEquals("SERVER_IDENTITY_UNVERIFIED", (error as LinkException.ServerError).code)
    }

    @Test
    fun `legacy server info without identity fields remains readable`() = runTest {
        val engine = MockEngine {
            respond(
                """{"service":"michi-micro-server","server":"Node","version":"1.0"}""",
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        assertTrue(client(engine = engine).getServerInfo().isSuccess)
    }

    @Test
    fun `HOME_MEMBERSHIP strategy is represented and still refused by the gate`() {
        val info = org.michimusic.link.dto.ServerInfoDto(
            auth = org.michimusic.link.dto.AuthInfoDto(strategy = "HOME_MEMBERSHIP"),
        )
        assertEquals(org.michimusic.link.dto.PairingStrategy.HOME_MEMBERSHIP, info.effectiveAuthStrategy)
        assertTrue(LinkAuthStrategyGate.refusal("HOME_MEMBERSHIP")?.startsWith("LINK_HOME_AUTH_NOT_IMPLEMENTED") == true)
    }

    @Test
    fun `home calls require HTTPS and an explicit pinned transport`() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders()) }
        val insecure = client(baseUrl = "http://127.0.0.1:7331", engine = engine)
            .homeEnrollmentChallenge(
                HomeEnrollmentChallengeRequestDto(
                    homeId = "h".repeat(43), clientMichiId = "c".repeat(43),
                    clientPublicKey = "p".repeat(43),
                ),
            )
        assertTrue(insecure.exceptionOrNull()?.message == "HOME_HTTPS_REQUIRED")

        val unpinned = client(pinned = false, engine = engine).homeAuthChallenge(
            HomeDeviceAuthChallengeRequestDto(
                clientMichiId = "c".repeat(43), clientPublicKey = "p".repeat(43), homeId = "h".repeat(43),
            ),
        )
        assertTrue(unpinned.exceptionOrNull()?.message == "HOME_TLS_PIN_REQUIRED")
    }

    @Test
    fun `home session token is published only after explicit verification and respects TTL`() {
        val client = client(engine = MockEngine { respond("{}") })
        assertFalse(client.isHomeSessionValid(nowMillis = 1_000))
        val verified = org.michimusic.link.home.VerifiedHomeSession(
            homeId = "h".repeat(43),
            serverMichiId = "s".repeat(43),
            clientMichiId = "c".repeat(43),
            sessionToken = "michi_home_abcdef",
            expiresInSeconds = 60,
            challengeId = "550e8400-e29b-41d4-a716-446655440111",
            serverMembership = org.michimusic.link.home.HomeMembershipDto(
                homeId = "h".repeat(43),
                deviceMichiId = "s".repeat(43),
                devicePublicKey = "p".repeat(43),
                deviceType = "server",
                roles = listOf("music_server"),
                issuedAt = "2026-11-18T12:00:00Z",
                serial = 1,
                signature = "x".repeat(86),
            ),
        )
        client.acceptVerifiedHomeSession(verified, nowMillis = 1_000)
        assertTrue(client.isHomeSessionValid(nowMillis = 59_000))
        assertFalse(client.isHomeSessionValid(nowMillis = 61_001))
        client.clearHomeSession()
        assertFalse(client.isHomeSessionValid(nowMillis = 2_000))
    }

    @Test
    fun `enrollment status sends the claim header on the exact status route`() = runTest {
        var claim = ""
        var path = ""
        val engine = MockEngine { request ->
            claim = request.headers["X-Michi-Enrollment-Claim"].orEmpty()
            path = request.url.encodedPath
            respond(
                """{"request_id":"r1","status":"pending","home_id":"${"h".repeat(43)}"}""",
                HttpStatusCode.OK, jsonHeaders(),
            )
        }
        val result = client(engine = engine).homeEnrollmentStatus("r1", "claim-secret")
        assertTrue(result.isSuccess)
        assertEquals("claim-secret", claim)
        assertEquals("/api/v1/home/enrollment/r1/status", path)
    }
}
