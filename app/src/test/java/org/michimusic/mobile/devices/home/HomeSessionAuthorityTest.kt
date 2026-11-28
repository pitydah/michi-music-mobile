package org.michimusic.mobile.devices.home

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.LinkClient
import org.michimusic.link.home.HomeEd25519
import org.michimusic.link.home.HomeMembershipDto
import org.michimusic.link.identity.MichiSigner
import org.michimusic.mobile.devices.aircat.AirCatHomeAnchor

class HomeSessionAuthorityTest {

    private val seed = ByteArray(32) { 0x33 }
    private val clientMichiId = "rz2gnw8LRcZPMln7OHAknoEL5Dg8TFyJ7utfFKJyPlE"
    private val clientPublicKey = "F8t5-ytBIPKx7GXkGY1uCLKOgT_rAeSkAIObheGAgM4"
    private val homeId = "SniHfEoJJvxdXLKCu0XBHISJsgL-GJD6DGWT5hkrn3U"
    private val rootKey = "0EqyMnQrtKs6E2i9RhXk5tAiSrcaAWuvhSCjMsl3hzc"
    private val serverMichiId = "NKN0pxzkWmTO9G3q4UWDq0oXVPjjU5tE80bIE4RIUcM"
    private val serverPublicKey = "oJql9HpnWYAv-VX43C0qFKXJnSO-l_hkEn_5ODRVpPA"
    private val authChallengeId = "550e8400-e29b-41d4-a716-446655440111"
    private val authNonce = "WnBfQ2hhbGxlbmdlTm9uY2VfMTIzNDU2"
    private val token = "michi_home_deterministic_golden_token_v1"
    private val serverSignature =
        "9dc8fk0sVb3TdZdqElA72asiPDhAW8tDcz7HbnlnLtxoV4-K_SUvEp6GCca5yotTK7TewjYeP_0FBb9W2wLjAw"

    private val membership = HomeMembershipDto(
        homeId = homeId,
        deviceMichiId = clientMichiId,
        devicePublicKey = clientPublicKey,
        deviceType = "mobile",
        roles = listOf("mobile_player", "remote_controller", "sync_client"),
        issuedAt = "2026-11-18T12:00:00Z",
        serial = 2,
        signature = "pKaiBn7O5Cxm3cgWE-d7q8a0kFFXqMF2oax4yffpXH-LoM45H4Rpp4QLEokCe8HhIqdFFZNGCfFGJAEgDfSGDw",
    )
    private val serverMembership = HomeMembershipDto(
        homeId = homeId,
        deviceMichiId = serverMichiId,
        devicePublicKey = serverPublicKey,
        deviceType = "server",
        roles = listOf("music_server", "library_host", "playback_host"),
        issuedAt = "2026-11-18T12:00:00Z",
        serial = 1,
        signature = "UIMvNv9CSFkAtskUZzhkmvbziWulUYULzskcDecTG4QFPzvY9OQi5uyDEw3Mp0ohIOwopbIIL90d8LyTe9BMBQ",
    )

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

    private fun authClient(engine: MockEngine): LinkClient = LinkClient.createForTest(
        baseUrl = "https://home.example:7443",
        clientDeviceId = clientMichiId,
        httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        },
        assumePinnedAirCatForTest = true,
    )

    private fun successEngine(counter: () -> Unit = {}): MockEngine = MockEngine { request ->
        when (request.url.encodedPath) {
            "/api/v1/auth/challenge" -> {
                counter()
                respond(
                    """{"challenge_id":"$authChallengeId","challenge_nonce":"$authNonce",
                       "server_michi_id":"$serverMichiId","server_public_key":"$serverPublicKey",
                       "expires_in":60}""",
                    HttpStatusCode.OK, headers(),
                )
            }
            "/api/v1/auth/session" -> respond(
                """{"session_token":"$token","token_type":"Bearer","expires_in":3600,
                   "server_michi_id":"$serverMichiId","server_membership":${json(serverMembership)},
                   "server_signature":"$serverSignature"}""",
                HttpStatusCode.OK, headers(),
            )
            else -> respond("{}", HttpStatusCode.NotFound, headers())
        }
    }

    private fun json(value: HomeMembershipDto): String = Json.encodeToString(HomeMembershipDto.serializer(), value)

    private fun authority(
        engine: MockEngine,
        store: HomeMembershipPersistence,
        now: () -> Long = { 0L },
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = StandardTestDispatcher(),
    ): HomeSessionAuthority {
        val coordinator = HomeEnrollmentCoordinator({ signer }, store)
        return HomeSessionAuthority(
            coordinator = coordinator,
            membershipStore = store,
            identityProvider = { signer },
            clientFactory = { _, _ -> authClient(engine) },
            monotonicClock = now,
            ioDispatcher = dispatcher,
        )
    }

    @Test
    fun `authenticate publishes a verified lease and reuses it without a second challenge`() = runTest {
        var challenges = 0
        val store = FakePersistence().apply { saveMembership(record()) }
        val authority = authority(successEngine { challenges++ }, store, dispatcher = StandardTestDispatcher(testScheduler))

        val first = authority.ensureAuthenticated(anchor).getOrThrow()
        val second = authority.ensureAuthenticated(anchor).getOrThrow()

        assertEquals(1, challenges)
        assertEquals(first.generation, second.generation)
        assertTrue(first.client.isHomeSessionValid())
        assertEquals(HomeSessionPhase.AUTHENTICATED, authority.state.value.phase)
        assertEquals(setOf("mobile_player", "remote_controller", "sync_client"), first.roles)
    }

    @Test
    fun `two concurrent callers share one single-flight authentication`() = runTest {
        var challenges = 0
        val store = FakePersistence().apply { saveMembership(record()) }
        val authority = authority(successEngine { challenges++ }, store, dispatcher = StandardTestDispatcher(testScheduler))

        val a = async { authority.ensureAuthenticated(anchor) }
        val b = async { authority.ensureAuthenticated(anchor) }
        assertTrue(a.await().isSuccess)
        assertTrue(b.await().isSuccess)
        assertEquals(1, challenges)
    }

    @Test
    fun `member revoked clears session and persisted membership and cannot resurect`() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            when (request.url.encodedPath) {
                "/api/v1/auth/challenge" -> respond(
                    """{"challenge_id":"$authChallengeId","challenge_nonce":"$authNonce",
                       "server_michi_id":"$serverMichiId","server_public_key":"$serverPublicKey",
                       "expires_in":60}""",
                    HttpStatusCode.OK, headers(),
                )
                else -> respond(
                    """{"error":{"code":"MEMBER_REVOKED","message":"member is revoked","details":{}}}""",
                    HttpStatusCode.Forbidden, headers(),
                )
            }
        }
        val store = FakePersistence().apply { saveMembership(record()) }
        val authority = authority(engine, store, dispatcher = StandardTestDispatcher(testScheduler))

        val first = authority.ensureAuthenticated(anchor)
        assertEquals("MEMBER_REVOKED", (first.exceptionOrNull() as HomeSessionException).code)
        assertNull(store.loadMembership(homeId))
        assertEquals(HomeSessionPhase.REVOKED, authority.state.value.phase)
        val requestsAfterFirst = requests

        val second = authority.ensureAuthenticated(anchor)
        assertEquals("MEMBER_REVOKED", (second.exceptionOrNull() as HomeSessionException).code)
        assertEquals(requestsAfterFirst, requests)
    }

    @Test
    fun `transient revocation store failure backs off instead of storming`() = runTest {
        var now = 1_000L
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(
                """{"error":{"code":"REVOCATION_STORE_UNAVAILABLE","message":"retry","details":{}}}""",
                HttpStatusCode.ServiceUnavailable, headers(),
            )
        }
        val store = FakePersistence().apply { saveMembership(record()) }
        val authority = authority(engine, store, now = { now }, dispatcher = StandardTestDispatcher(testScheduler))

        val first = authority.ensureAuthenticated(anchor)
        assertEquals("REVOCATION_STORE_UNAVAILABLE", (first.exceptionOrNull() as HomeSessionException).code)
        val second = authority.ensureAuthenticated(anchor)
        assertEquals("BACKOFF", (second.exceptionOrNull() as HomeSessionException).code)
        assertEquals(1, requests)

        now = 40_000L
        val third = authority.ensureAuthenticated(anchor)
        assertEquals("REVOCATION_STORE_UNAVAILABLE", (third.exceptionOrNull() as HomeSessionException).code)
        assertEquals(2, requests)
    }

    @Test
    fun `invalidate drops the old generation but allows a fresh reauth`() = runTest {
        val store = FakePersistence().apply { saveMembership(record()) }
        val authority = authority(successEngine(), store, dispatcher = StandardTestDispatcher(testScheduler))
        val first = authority.ensureAuthenticated(anchor).getOrThrow()
        authority.invalidate(homeId, "network changed", clearMembership = false)
        val second = authority.ensureAuthenticated(anchor).getOrThrow()
        assertTrue(second.generation > first.generation)
        assertFalse(second.client === first.client)
        assertTrue(second.client.isHomeSessionValid())
    }

    private fun record() = HomeMembershipStore.MembershipRecord(
        homeId = homeId,
        sourceMichiId = serverMichiId,
        membership = membership,
        storedAtMillis = 1L,
    )

    private class FakePersistence : HomeMembershipPersistence {
        private val memberships = mutableMapOf<String, HomeMembershipStore.MembershipRecord>()
        override fun savePending(pending: HomeMembershipStore.PendingEnrollment): Boolean = true
        override fun loadPending(homeId: String): HomeMembershipStore.PendingEnrollment? = null
        override fun clearPending(homeId: String): Boolean = true
        override fun saveMembership(record: HomeMembershipStore.MembershipRecord): Boolean {
            memberships[record.homeId] = record; return true
        }
        override fun loadMembership(homeId: String): HomeMembershipStore.MembershipRecord? = memberships[homeId]
        override fun clearMembership(homeId: String): Boolean {
            memberships.remove(homeId); return true
        }
    }
}
