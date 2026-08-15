package org.michimusic.link.identity

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class QrPairingParserTest {

    private lateinit var identity: MichiIdentity
    private lateinit var parser: QrPairingParser

    @Before
    fun setup() {
        identity = mockk(relaxed = true)
        parser = QrPairingParser(identity)
    }

    @Test
    fun `parseAndValidate parses canonical QR URI correctly`() {
        val futureExpires = Instant.now().plusSeconds(300).toString()
        val qrUri = "michi://pair?format=michi-link-pairing&version=1&server_michi_id=michi_srv_1&server_public_key=pk_base64&session_id=550e8400-e29b-41d4-a716-446655440000&expires_at=$futureExpires&endpoint=http://192.168.1.50:7331"

        every { identity.verifyServerIdentity("michi_srv_1", "pk_base64") } returns true

        val result = parser.parseAndValidate(qrUri)
        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals("michi_srv_1", data.serverMichiId)
        assertEquals("pk_base64", data.serverPublicKey)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", data.sessionId)
        assertEquals("http://192.168.1.50:7331", data.endpoint)
    }

    @Test
    fun `parseAndValidate rejects URI longer than 1024 bytes`() {
        val futureExpires = Instant.now().plusSeconds(300).toString()
        val padding = "a".repeat(1100)
        val qrUri = "michi://pair?format=michi-link-pairing&version=1&server_michi_id=michi_srv_1&server_public_key=$padding&session_id=550e8400-e29b-41d4-a716-446655440000&expires_at=$futureExpires&endpoint=http://192.168.1.50:7331"

        val result = parser.parseAndValidate(qrUri)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("longitud máxima") == true)
    }

    @Test
    fun `parseAndValidate rejects non-UUID session_id`() {
        val futureExpires = Instant.now().plusSeconds(300).toString()
        val qrUri = "michi://pair?format=michi-link-pairing&version=1&server_michi_id=michi_srv_1&server_public_key=pk_base64&session_id=invalid-session-id&expires_at=$futureExpires&endpoint=http://192.168.1.50:7331"

        every { identity.verifyServerIdentity(any(), any()) } returns true

        val result = parser.parseAndValidate(qrUri)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("UUID") == true)
    }

    @Test
    fun `parseAndValidate rejects non-HTTP endpoint`() {
        val futureExpires = Instant.now().plusSeconds(300).toString()
        val qrUri = "michi://pair?format=michi-link-pairing&version=1&server_michi_id=michi_srv_1&server_public_key=pk_base64&session_id=550e8400-e29b-41d4-a716-446655440000&expires_at=$futureExpires&endpoint=ftp://192.168.1.50:7331"

        every { identity.verifyServerIdentity(any(), any()) } returns true

        val result = parser.parseAndValidate(qrUri)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("HTTP o HTTPS") == true)
    }

    @Test
    fun `parseAndValidate rejects identity mismatch`() {
        val futureExpires = Instant.now().plusSeconds(300).toString()
        val qrUri = "michi://pair?format=michi-link-pairing&version=1&server_michi_id=michi_srv_1&server_public_key=pk_base64&session_id=550e8400-e29b-41d4-a716-446655440000&expires_at=$futureExpires&endpoint=http://192.168.1.50:7331"

        every { identity.verifyServerIdentity("michi_srv_1", "pk_base64") } returns false

        val result = parser.parseAndValidate(qrUri)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Identity mismatch") == true)
    }
}
