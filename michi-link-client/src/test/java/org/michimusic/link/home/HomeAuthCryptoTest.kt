package org.michimusic.link.home

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIM-02 golden-vector proof against `pitydah/michi-link` at 8dad0fb5
 * (`tests/vectors/interop/micro-mobile-interop-vectors.json`). These are the
 * bytes Micro Server verifies, not a paraphrase of the handoff document.
 */
class HomeAuthCryptoTest {

    private val clientSeed = ByteArray(32) { 0x33 }
    private val duplicateSeed = ByteArray(32) { 0x44 }
    private val clientKey = Ed25519PrivateKeyParameters(clientSeed, 0)
    private val clientPublicKey = "F8t5-ytBIPKx7GXkGY1uCLKOgT_rAeSkAIObheGAgM4"
    private val clientMichiId = "rz2gnw8LRcZPMln7OHAknoEL5Dg8TFyJ7utfFKJyPlE"
    private val homeId = "SniHfEoJJvxdXLKCu0XBHISJsgL-GJD6DGWT5hkrn3U"
    private val homeRootPublicKey = "0EqyMnQrtKs6E2i9RhXk5tAiSrcaAWuvhSCjMsl3hzc"
    private val serverMichiId = "NKN0pxzkWmTO9G3q4UWDq0oXVPjjU5tE80bIE4RIUcM"
    private val serverPublicKey = "oJql9HpnWYAv-VX43C0qFKXJnSO-l_hkEn_5ODRVpPA"

    private val enrollmentChallengeId = "550e8400-e29b-41d4-a716-446655440099"
    private val enrollmentNonce = "VFfZjzw8JeAM7-RFiTSrMA"
    private val enrollmentExpectedPayload =
        "michi-link-home-enrollment-pop-v1$homeId$serverMichiId$clientMichiId$clientPublicKey" +
            "$enrollmentChallengeId$enrollmentNonce"
    private val enrollmentExpectedSignature =
        "aPjc7KhYzS5WiexMph2QLGAtZkGEmZDw62i1Xip203XgSYlZpaFcsHxkZ_5iBp25yatLRlQ4T0ptaguS925FDQ"

    private val authChallengeId = "550e8400-e29b-41d4-a716-446655440111"
    private val authNonce = "WnBfQ2hhbGxlbmdlTm9uY2VfMTIzNDU2"
    private val authExpectedPayload =
        "michi-link-device-auth-v1$homeId$serverMichiId$clientMichiId$authChallengeId$authNonce"
    private val authExpectedSignature =
        "BJtkvwhY-jRK_uM1wKGOSWZuG7ocJ9eukBdrkHYS5vSlTWCQEkG_1qNvv9Ni8dq03mWQmcrlukbmONrQ7oUjAg"

    private val sessionToken = "michi_home_deterministic_golden_token_v1"
    private val serverAuthExpectedPayload =
        "michi-link-server-auth-v1$homeId$serverMichiId$clientMichiId$authChallengeId$sessionToken"
    private val serverAuthExpectedSignature =
        "9dc8fk0sVb3TdZdqElA72asiPDhAW8tDcz7HbnlnLtxoV4-K_SUvEp6GCca5yotTK7TewjYeP_0FBb9W2wLjAw"

    private val clientMembership = HomeMembershipDto(
        deviceMichiId = clientMichiId,
        devicePublicKey = clientPublicKey,
        deviceType = "mobile",
        homeId = homeId,
        issuedAt = "2026-11-18T12:00:00Z",
        roles = listOf("mobile_player", "remote_controller", "sync_client"),
        serial = 2,
        signature = "pKaiBn7O5Cxm3cgWE-d7q8a0kFFXqMF2oax4yffpXH-LoM45H4Rpp4QLEokCe8HhIqdFFZNGCfFGJAEgDfSGDw",
    )

    private val serverMembership = HomeMembershipDto(
        deviceMichiId = serverMichiId,
        devicePublicKey = serverPublicKey,
        deviceType = "server",
        homeId = homeId,
        issuedAt = "2026-11-18T12:00:00Z",
        roles = listOf("music_server", "library_host", "playback_host"),
        serial = 1,
        signature = "UIMvNv9CSFkAtskUZzhkmvbziWulUYULzskcDecTG4QFPzvY9OQi5uyDEw3Mp0ohIOwopbIIL90d8LyTe9BMBQ",
    )

    @Test
    fun `michi id is base64url BLAKE3 of the raw public key`() {
        assertEquals(clientMichiId, MichiIdentityHash.deriveMichiId(clientPublicKey))
        assertTrue(MichiIdentityHash.isMichiId(clientMichiId))
        assertFalse(MichiIdentityHash.isMichiId("michi_home_${"a".repeat(32)}"))
        assertEquals(homeId, MichiIdentityHash.deriveMichiId(homeRootPublicKey))
    }

    @Test
    fun `strict base64url rejects padding standard alphabet and whitespace`() {
        assertNotNull(MichiBase64Url.decode(clientPublicKey))
        assertNull(MichiBase64Url.decode("$clientPublicKey="))
        assertNull(MichiBase64Url.decode(clientPublicKey.replace('-', '+')))
        assertNull(MichiBase64Url.decode(" $clientPublicKey"))
        assertNull(MichiBase64Url.decodeExactly(clientPublicKey, 31))
    }

    @Test
    fun `enrollment proof payload and signature match golden vectors byte for byte`() {
        val payload = HomeAuthTranscript.enrollmentPop(
            homeId, serverMichiId, clientMichiId, clientPublicKey,
            enrollmentChallengeId, enrollmentNonce,
        )
        assertEquals(enrollmentExpectedPayload, payload.toString(Charsets.UTF_8))
        val signature = HomeEd25519.sign(clientKey, payload)
        assertEquals(enrollmentExpectedSignature, signature)
        assertTrue(HomeEd25519.verify(clientPublicKey, signature, payload))
    }

    @Test
    fun `device auth payload and signature match golden vectors`() {
        val payload = HomeAuthTranscript.deviceAuth(
            homeId, serverMichiId, clientMichiId, authChallengeId, authNonce,
        )
        assertEquals(authExpectedPayload, payload.toString(Charsets.UTF_8))
        assertEquals(authExpectedSignature, HomeEd25519.sign(clientKey, payload))
    }

    @Test
    fun `server confirmation signature verifies only over the session token transcript`() {
        val payload = HomeAuthTranscript.serverAuth(
            homeId, serverMichiId, clientMichiId, authChallengeId, sessionToken,
        )
        assertEquals(serverAuthExpectedPayload, payload.toString(Charsets.UTF_8))
        assertTrue(HomeEd25519.verify(serverPublicKey, serverAuthExpectedSignature, payload))
        assertFalse(
            HomeEd25519.verify(
                serverPublicKey,
                serverAuthExpectedSignature,
                HomeAuthTranscript.serverAuth(
                    homeId, serverMichiId, clientMichiId, authChallengeId, authNonce,
                ),
            ),
        )
    }

    @Test
    fun `membership certificate verifies against the Home root with sorted roles and serial`() {
        assertEquals(
            HomeVerification.Valid,
            HomeMembershipVerifier.verify(clientMembership, homeRootPublicKey, homeId),
        )
        assertEquals(
            HomeVerification.Valid,
            HomeMembershipVerifier.verify(serverMembership, homeRootPublicKey, homeId),
        )
    }

    @Test
    fun `membership tampering fails closed`() {
        val wrongSerial = clientMembership.copy(serial = 3)
        assertTrue(
            HomeMembershipVerifier.verify(wrongSerial, homeRootPublicKey, homeId) is HomeVerification.Invalid,
        )
        val wrongRole = clientMembership.copy(roles = clientMembership.roles + "library_master")
        assertTrue(
            HomeMembershipVerifier.verify(wrongRole, homeRootPublicKey, homeId) is HomeVerification.Invalid,
        )
        val wrongHome = clientMembership.copy(homeId = serverMichiId)
        assertTrue(
            HomeMembershipVerifier.verify(wrongHome, homeRootPublicKey, homeId) is HomeVerification.Invalid,
        )
        assertTrue(
            HomeMembershipVerifier.verify(clientMembership, serverPublicKey, homeId) is HomeVerification.Invalid,
        )
    }

    @Test
    fun `revoked member never verifies even with a valid certificate`() {
        val result = HomeMembershipVerifier.verify(
            clientMembership, homeRootPublicKey, homeId, setOf(clientMichiId),
        )
        assertEquals(HomeVerification.Invalid("MEMBER_REVOKED"), result)
    }

    @Test
    fun `server session verifies membership and server signature before token publication`() {
        val response = HomeAuthSessionResponseDto(
            sessionToken = sessionToken,
            tokenType = "Bearer",
            expiresIn = 3600,
            serverMichiId = serverMichiId,
            serverMembership = serverMembership,
            serverSignature = serverAuthExpectedSignature,
        )
        assertEquals(
            HomeVerification.Valid,
            HomeServerSessionVerifier.verify(
                response, homeRootPublicKey, homeId, serverMichiId,
                clientMichiId, authChallengeId,
            ),
        )
        assertTrue(
            HomeServerSessionVerifier.verify(
                response.copy(serverSignature = "A".repeat(86)),
                homeRootPublicKey, homeId, serverMichiId, clientMichiId, authChallengeId,
            ) is HomeVerification.Invalid,
        )
        assertTrue(
            HomeServerSessionVerifier.verify(
                response.copy(serverMichiId = clientMichiId),
                homeRootPublicKey, homeId, serverMichiId, clientMichiId, authChallengeId,
            ) is HomeVerification.Invalid,
        )
    }

    @Test
    fun `a different private key can never produce the golden signature`() {
        val payload = HomeAuthTranscript.enrollmentPop(
            homeId, serverMichiId, clientMichiId, clientPublicKey,
            enrollmentChallengeId, enrollmentNonce,
        )
        val other = HomeEd25519.sign(Ed25519PrivateKeyParameters(duplicateSeed, 0), payload)
        assertFalse(HomeEd25519.verify(clientPublicKey, other, payload))
    }
}
