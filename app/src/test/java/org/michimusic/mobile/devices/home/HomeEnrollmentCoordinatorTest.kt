package org.michimusic.mobile.devices.home

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.LinkClient
import org.michimusic.link.home.HomeAuthTranscript
import org.michimusic.link.home.HomeEd25519
import org.michimusic.link.identity.MichiSigner
import org.michimusic.mobile.devices.aircat.AirCatHomeAnchor

class HomeEnrollmentCoordinatorTest {

    private val seed = ByteArray(32) { 0x33 }
    private val clientMichiId = "rz2gnw8LRcZPMln7OHAknoEL5Dg8TFyJ7utfFKJyPlE"
    private val clientPublicKey = "F8t5-ytBIPKx7GXkGY1uCLKOgT_rAeSkAIObheGAgM4"
    private val homeId = "SniHfEoJJvxdXLKCu0XBHISJsgL-GJD6DGWT5hkrn3U"
    private val rootKey = "0EqyMnQrtKs6E2i9RhXk5tAiSrcaAWuvhSCjMsl3hzc"
    private val serverMichiId = "NKN0pxzkWmTO9G3q4UWDq0oXVPjjU5tE80bIE4RIUcM"
    private val serverPublicKey = "oJql9HpnWYAv-VX43C0qFKXJnSO-l_hkEn_5ODRVpPA"
    private val challengeId = "550e8400-e29b-41d4-a716-446655440099"
    private val authChallengeId = "550e8400-e29b-41d4-a716-446655440111"
    private val nonce = "VFfZjzw8JeAM7-RFiTSrMA"
    private val authNonce = "WnBfQ2hhbGxlbmdlTm9uY2VfMTIzNDU2"
    private val sessionToken = "michi_home_deterministic_golden_token_v1"

    private val membership = """
        {"version":1,"home_id":"$homeId","device_michi_id":"$clientMichiId",
         "device_public_key":"$clientPublicKey","device_type":"mobile",
         "roles":["mobile_player","remote_controller","sync_client"],
         "issued_at":"2026-11-18T12:00:00Z","serial":2,
         "signature":"pKaiBn7O5Cxm3cgWE-d7q8a0kFFXqMF2oax4yffpXH-LoM45H4Rpp4QLEokCe8HhIqdFFZNGCfFGJAEgDfSGDw"}
    """.trimIndent()

    private val serverMembership = """
        {"version":1,"home_id":"$homeId","device_michi_id":"$serverMichiId",
         "device_public_key":"$serverPublicKey","device_type":"server",
         "roles":["music_server","library_host","playback_host"],
         "issued_at":"2026-11-18T12:00:00Z","serial":1,
         "signature":"UIMvNv9CSFkAtskUZzhkmvbziWulUYULzskcDecTG4QFPzvY9OQi5uyDEw3Mp0ohIOwopbIIL90d8LyTe9BMBQ"}
    """.trimIndent()

    private val anchor = AirCatHomeAnchor(
        homeId = homeId,
        authorityMichiId = homeId,
        authorityPublicKeyBase64Url = rootKey,
        sourceMichiId = serverMichiId,
        tlsSpkiSha256 = "sha256/" + "A".repeat(44),
        pinnedBaseUrl = "https://home.example:7443",
    )

    private val signer = object : MichiSigner {
        private val key = Ed25519PrivateKeyParameters(seed, 0)
        override val publicKeyBase64Url = clientPublicKey
        override val michiId = clientMichiId
        override fun signCanonicalPayload(payload: ByteArray): String = HomeEd25519.sign(key, payload)
    }

    private fun headers() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun client(engine: MockEngine): LinkClient = LinkClient.createForTest(
        baseUrl = "https://home.example:7443",
        clientDeviceId = clientMichiId,
        httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        },
        assumePinnedAirCatForTest = true,
    )

    private fun fake() = FakePersistence()

    @Test
    fun `request enrollment signs canonical pop and persists only pending state`() = runTest {
        var requestBody = ""
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/home/enrollment/challenge" -> respond(
                    """{"challenge_id":"$challengeId","challenge_nonce":"$nonce",
                       "home_id":"$homeId","server_michi_id":"$serverMichiId",
                       "server_public_key":"$serverPublicKey","expires_in":120}""",
                    HttpStatusCode.OK, headers(),
                )
                "/api/v1/home/enrollment/request" -> {
                    requestBody = request.body.toByteArray().decodeToString()
                    respond(
                        """{"request_id":"550e8400-e29b-41d4-a716-446655440099",
                           "status":"pending","home_id":"$homeId","client_michi_id":"$clientMichiId",
                           "expires_at":"2026-11-18T12:10:00Z",
                           "claim_token":"claim-token-0123456789abcdef"}""",
                        HttpStatusCode.Accepted, headers(),
                    )
                }
                else -> respond("{}", HttpStatusCode.NotFound, headers())
            }
        }
        val store = fake()
        val coordinator = HomeEnrollmentCoordinator({ signer }, store) { _, _, _ -> client(engine) }
        val outcome = coordinator.requestEnrollment(anchor, "Pixel de Cristian")
        assertTrue("unexpected $outcome", outcome is HomeEnrollmentCoordinator.Outcome.Pending)
        assertEquals(challengeId, (outcome as HomeEnrollmentCoordinator.Outcome.Pending).requestId)
        assertEquals(1, store.pending.size)
        assertTrue(store.memberships.isEmpty())
        assertTrue(requestBody.contains("\"client_signature\":\"aPjc7KhYzS5WiexMph2QLGAtZkGEmZDw62i1Xip203XgSYlZpaFcsHxkZ_5iBp25yatLRlQ4T0ptaguS925FDQ\""))
        assertTrue(requestBody.contains("\"device_type\":\"mobile\""))
        assertTrue(requestBody.contains("\"requested_roles\":[\"mobile_player\",\"remote_controller\",\"sync_client\"]"))
    }

    @Test
    fun `approved status verifies membership against the QR root before persisting`() = runTest {
        val store = fake()
        store.savePending(
            HomeMembershipStore.PendingEnrollment(
                challengeId, "claim-token-0123456789abcdef", homeId, serverMichiId,
                "Pixel", listOf("mobile_player"), "", 1L,
            ),
        )
        var calls = 0
        val engine = MockEngine {
            calls++
            val status = if (calls == 1) "pending" else "approved"
            val approvedFields = if (status == "approved")
                ""","home_root_public_key":"$rootKey","membership":$membership""" else ""
            respond(
                """{"request_id":"$challengeId","status":"$status","home_id":"$homeId"$approvedFields}""",
                HttpStatusCode.OK, headers(),
            )
        }
        val coordinator = HomeEnrollmentCoordinator({ signer }, store) { _, _, _ -> client(engine) }
        assertTrue(coordinator.refreshEnrollment(anchor) is HomeEnrollmentCoordinator.Outcome.Pending)
        val approved = coordinator.refreshEnrollment(anchor)
        assertTrue("unexpected $approved", approved is HomeEnrollmentCoordinator.Outcome.EnrolledOffline)
        assertEquals(clientMichiId, store.memberships[homeId]?.membership?.deviceMichiId)
        assertNull(store.pending[homeId])
    }

    @Test
    fun `tampered membership is never persisted`() = runTest {
        val store = fake()
        store.savePending(
            HomeMembershipStore.PendingEnrollment(
                challengeId, "claim-token-0123456789abcdef", homeId, serverMichiId,
                "Pixel", listOf("mobile_player"), "", 1L,
            ),
        )
        val tampered = membership.replace(
            "pKaiBn7O5Cxm3cgWE-d7q8a0kFFXqMF2oax4yffpXH-LoM45H4Rpp4QLEokCe8HhIqdFFZNGCfFGJAEgDfSGDw",
            "A".repeat(86),
        )
        val engine = MockEngine {
            respond(
                """{"request_id":"$challengeId","status":"approved","home_id":"$homeId",
                    "home_root_public_key":"$rootKey","membership":$tampered}""",
                HttpStatusCode.OK, headers(),
            )
        }
        val coordinator = HomeEnrollmentCoordinator({ signer }, store) { _, _, _ -> client(engine) }
        val outcome = coordinator.refreshEnrollment(anchor)
        assertTrue("unexpected $outcome", outcome is HomeEnrollmentCoordinator.Outcome.Failure)
        assertEquals("INVALID_MEMBERSHIP", (outcome as HomeEnrollmentCoordinator.Outcome.Failure).code)
        assertTrue(store.memberships.isEmpty())
    }

    @Test
    fun `root key rotation without a fresh QR fails closed`() = runTest {
        val store = fake()
        store.savePending(
            HomeMembershipStore.PendingEnrollment(
                challengeId, "claim-token-0123456789abcdef", homeId, serverMichiId,
                "Pixel", listOf("mobile_player"), "", 1L,
            ),
        )
        val engine = MockEngine {
            respond(
                """{"request_id":"$challengeId","status":"approved","home_id":"$homeId",
                    "home_root_public_key":"$serverPublicKey","membership":$membership}""",
                HttpStatusCode.OK, headers(),
            )
        }
        val coordinator = HomeEnrollmentCoordinator({ signer }, store) { _, _, _ -> client(engine) }
        val outcome = coordinator.refreshEnrollment(anchor)
        assertEquals(
            "ROOT_KEY_CHANGED",
            (outcome as HomeEnrollmentCoordinator.Outcome.Failure).code,
        )
        assertTrue(store.memberships.isEmpty())
    }

    @Test
    fun `authenticate publishes token only after server membership and signature verify`() = runTest {
        val store = fake()
        store.saveMembership(
            HomeMembershipStore.MembershipRecord(
                homeId, serverMichiId,
                Json.decodeFromString(membership), 1L,
            ),
        )
        val serverSignature = "9dc8fk0sVb3TdZdqElA72asiPDhAW8tDcz7HbnlnLtxoV4-K_SUvEp6GCca5yotTK7TewjYeP_0FBb9W2wLjAw"
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/challenge" -> respond(
                    """{"challenge_id":"$authChallengeId","challenge_nonce":"$authNonce",
                       "server_michi_id":"$serverMichiId","server_public_key":"$serverPublicKey",
                       "expires_in":60}""",
                    HttpStatusCode.OK, headers(),
                )
                "/api/v1/auth/session" -> respond(
                    """{"session_token":"$sessionToken","token_type":"Bearer","expires_in":3600,
                       "server_michi_id":"$serverMichiId","server_membership":$serverMembership,
                       "server_signature":"$serverSignature"}""",
                    HttpStatusCode.OK, headers(),
                )
                else -> respond("{}", HttpStatusCode.NotFound, headers())
            }
        }
        val sessionClient = client(engine)
        val coordinator = HomeEnrollmentCoordinator({ signer }, store) { _, _, _ -> client(engine) }
        val outcome = coordinator.authenticate(anchor, sessionClient)
        assertTrue("unexpected $outcome", outcome is HomeEnrollmentCoordinator.AuthOutcome.Authenticated)
        assertTrue(sessionClient.isHomeSessionValid())
        sessionClient.close()
    }

    @Test
    fun `hostile server confirmation cannot publish a token`() = runTest {
        val store = fake()
        store.saveMembership(
            HomeMembershipStore.MembershipRecord(
                homeId, serverMichiId,
                Json.decodeFromString(membership), 1L,
            ),
        )
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/challenge" -> respond(
                    """{"challenge_id":"$authChallengeId","challenge_nonce":"$authNonce",
                       "server_michi_id":"$serverMichiId","server_public_key":"$serverPublicKey",
                       "expires_in":60}""",
                    HttpStatusCode.OK, headers(),
                )
                else -> respond(
                    """{"session_token":"$sessionToken","token_type":"Bearer","expires_in":3600,
                       "server_michi_id":"$serverMichiId","server_membership":$serverMembership,
                       "server_signature":"${"A".repeat(86)}"}""",
                    HttpStatusCode.OK, headers(),
                )
            }
        }
        val sessionClient = client(engine)
        val coordinator = HomeEnrollmentCoordinator({ signer }, store) { _, _, _ -> client(engine) }
        val outcome = coordinator.authenticate(anchor, sessionClient)
        assertEquals(
            "SERVER_AUTH_REJECTED",
            (outcome as HomeEnrollmentCoordinator.AuthOutcome.Failure).code,
        )
        assertTrue(!sessionClient.isHomeSessionValid())
        sessionClient.close()
    }

    @Test
    fun `expired enrollment clears the pending claim`() = runTest {
        val store = fake()
        store.savePending(
            HomeMembershipStore.PendingEnrollment(
                challengeId, "claim-token-0123456789abcdef", homeId, serverMichiId,
                "Pixel", listOf("mobile_player"), "", 1L,
            ),
        )
        val engine = MockEngine {
            respond(
                """{"request_id":"$challengeId","status":"expired","home_id":"$homeId"}""",
                HttpStatusCode.OK, headers(),
            )
        }
        val coordinator = HomeEnrollmentCoordinator({ signer }, store) { _, _, _ -> client(engine) }
        val outcome = coordinator.refreshEnrollment(anchor)
        assertEquals("expired", (outcome as HomeEnrollmentCoordinator.Outcome.Rejected).status)
        assertTrue(store.pending.isEmpty())
    }

    private class FakePersistence : HomeMembershipPersistence {
        val pending = mutableMapOf<String, HomeMembershipStore.PendingEnrollment>()
        val memberships = mutableMapOf<String, HomeMembershipStore.MembershipRecord>()
        override fun savePending(pending: HomeMembershipStore.PendingEnrollment): Boolean {
            this.pending[pending.homeId] = pending; return true
        }
        override fun loadPending(homeId: String) = pending[homeId]
        override fun clearPending(homeId: String): Boolean { pending.remove(homeId); return true }
        override fun saveMembership(record: HomeMembershipStore.MembershipRecord): Boolean {
            memberships[record.homeId] = record; return true
        }
        override fun loadMembership(homeId: String) = memberships[homeId]
        override fun clearMembership(homeId: String): Boolean {
            memberships.remove(homeId); pending.remove(homeId); return true
        }
    }
}
