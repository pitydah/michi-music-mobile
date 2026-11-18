package org.michimusic.mobile.devices.aircat

import io.mockk.every
import org.bouncycastle.crypto.digests.Blake3Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.michimusic.link.LinkClient
import org.michimusic.link.port.PinnedOriginalFileSource
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * A27/A67: no AirCat transfer or recovery leaves the device without a Home grant.
 *
 * These end-to-end tests exercise the transfer machinery — checkpoints, pause,
 * resume, cancellation, Room persistence — and every one of those paths now runs
 * behind a signed grant. Minting a real Ed25519 grant here keeps the subject of
 * the test the transfer contract itself, instead of a bypass that production no
 * longer has.
 *
 * The anchor mirrors whichever source id a test declares, so a failure in these
 * tests can never be "the fixture forgot to enrol the peer".
 */
internal class AirCatTestAuthority {
    companion object {
        /** The single endpoint this Home enrolled. Anything else must be refused. */
        const val PINNED = "https://source.example"
        private const val TLS_PIN_FILL: Byte = 11
    }

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val authorityKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val authorityPublic = authorityKey.generatePublicKey().encoded
    private val authorityId = michiIdOf(authorityPublic)
    private val tlsPin = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32) { TLS_PIN_FILL })

    private val homeAnchor: AirCatHomeAnchor
        get() = AirCatHomeAnchor(
            homeId = authorityId,
            authorityMichiId = authorityId,
            authorityPublicKeyBase64Url = encoder.encodeToString(authorityPublic),
            sourceMichiId = "",
            tlsSpkiSha256 = tlsPin,
            pinnedBaseUrl = PINNED,
        )

    val security = AirCatSignedGrantSecurity(object : AirCatHomeAuthorityProvider {
        override fun anchorFor(homeId: String): AirCatHomeAnchor? =
            homeAnchor.takeIf { it.homeId == homeId }

        override fun anchorForSource(sourceMichiId: String): AirCatHomeAnchor? =
            homeAnchor.copy(sourceMichiId = sourceMichiId)
    })

    /** Grant ids must be canonical 43-character Link ids, not human tags. */
    fun mid(tag: String): String = tag.padEnd(43, 'x').take(43)

    fun michiIdOf(publicKey: ByteArray): String {
        val digest = Blake3Digest(256).also { it.update(publicKey, 0, publicKey.size) }
        return encoder.encodeToString(ByteArray(32).also { digest.doFinal(it, 0) })
    }

    /** Returns the intent carrying a grant for exactly this manifest and transfer. */
    fun authorized(intent: TransferIntent, manifest: AirCatManifest): TransferIntent {
        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val nonceBase64Url = encoder.encodeToString(nonce)
        val issuedAt = System.currentTimeMillis()
        val manifestSha = AirCatCanonicalWire.manifestSha256(manifest)
            ?: error("the fixture manifest is not canonical, so it cannot be authorised")
        val grant = AirCatSignedGrant(
            version = 1,
            homeId = authorityId,
            authorityMichiId = authorityId,
            transferId = intent.transferId,
            sourceMichiId = intent.sourceMichiId,
            destinationMichiId = intent.destinationMichiId,
            sourceLibraryId = intent.sourceLibraryId,
            destinationLibraryId = intent.destinationLibraryId,
            selectionScope = intent.selectionScope,
            scopes = listOf("content:read", "content:transfer"),
            manifestSha256 = manifestSha,
            issuedAtMillis = issuedAt,
            expiresAtMillis = minOf(issuedAt + 300_000L, intent.expiresAt),
            nonceBase64Url = nonceBase64Url,
            signatureBase64Url = "",
        )
        val bytes = AirCatCanonicalWire.signedGrantBytes(grant)
        val signer = Ed25519Signer().also {
            it.init(true, authorityKey)
            it.update(bytes, 0, bytes.size)
        }
        return intent.copy(
            signedGrant = grant.copy(
                signatureBase64Url = encoder.encodeToString(signer.generateSignature()),
            ),
            authorizationNonceBase64Url = nonceBase64Url,
        )
    }

    /**
     * Pins a Link client to the enrolled endpoint, gives it the session token the
     * runtime guard will compare, and hands out its byte source.
     */
    fun pin(client: LinkClient, token: String = "aircat-test-token"): LinkClient {
        every { client.baseUrl } returns PINNED
        every { client.deviceToken } returns token
        every { client.pinnedOriginalFileSource(any(), any()) } returns sourceOf(client)
        return client
    }

    private fun sourceOf(client: LinkClient) = object : PinnedOriginalFileSource {
        override val servesOriginalBytes: Boolean get() = true
        override val verifiedTransport: Boolean get() = true
        override fun close() = Unit
        override suspend fun streamTrackRange(
            trackId: String,
            outputStream: OutputStream,
            startBytes: Long,
            expectedEtag: String?,
            bufferSize: Int,
            onProgress: ((bytesRead: Long, totalExpected: Long?) -> Unit)?,
        ) = client.streamTrackRange(
            trackId, outputStream, startBytes, expectedEtag, bufferSize, onProgress,
        )
    }
}
