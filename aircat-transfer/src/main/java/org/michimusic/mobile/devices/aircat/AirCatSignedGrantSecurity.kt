package org.michimusic.mobile.devices.aircat

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.digests.Blake3Digest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * A27: transport-independent, signed AirCat v1 approval.
 *
 * The Home authority public key and TLS SPKI pin are installed ONLY by a
 * previously authenticated Home enrollment. The caller must not derive them
 * from UDP discovery, /server/info, or a signature included in the grant.
 * Signed grants are not a replacement for TLS or the producer's per-request
 * authorization, replay cache, file ACLs and revocation checks.
 */
interface AirCatHomeAuthorityProvider {
    fun anchorFor(homeId: String): AirCatHomeAnchor?
    // Enrolment index. A device advertised by UDP is NOT a Home anchor.
    fun anchorForSource(sourceMichiId: String): AirCatHomeAnchor? = null

    object Unavailable : AirCatHomeAuthorityProvider {
        override fun anchorFor(homeId: String): AirCatHomeAnchor? = null
    }
}

data class AirCatHomeAnchor(
    val homeId: String,
    val authorityMichiId: String,
    val authorityPublicKeyBase64Url: String,
    val sourceMichiId: String,
    val tlsSpkiSha256: String,
    /** A28: QR-enrolled origin endpoint. No redirects or DNS-host substitution. */
    val pinnedBaseUrl: String = "",
)

/**
 * A27: source-controlled authorization request over pinned HTTPS.
 * Producer MUST re-check file ACLs, Home membership and actual original bytes,
 * not merely trust the manifest supplied by Mobile.
 */
@kotlinx.serialization.Serializable
data class AirCatGrantRequest(
    val intent: TransferIntent,
    val manifest: AirCatManifest,
    val manifestSha256: String,
    val clientNonceBase64Url: String,
) {
    companion object {
        fun create(intent: TransferIntent, manifest: AirCatManifest): AirCatGrantRequest {
            require(intent.signedGrant == null) { "Grant request cannot contain a grant" }
            require(intent.authorizationNonceBase64Url.isEmpty()) { "Fresh authorization required" }
            val sha = AirCatCanonicalWire.manifestSha256(manifest)
                ?: throw IllegalArgumentException("Invalid original manifest")
            val nonce = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
            return AirCatGrantRequest(intent, manifest, sha,
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce))
        }
    }
}

/** Signed on-wire fields; the signature covers the complete canonical grant. */
@kotlinx.serialization.Serializable
data class AirCatSignedGrant(
    val version: Int,
    val homeId: String,
    val authorityMichiId: String,
    val transferId: String,
    val sourceMichiId: String,
    val destinationMichiId: String,
    val sourceLibraryId: String,
    val destinationLibraryId: String,
    val selectionScope: String,
    val scopes: List<String>,
    val manifestSha256: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val nonceBase64Url: String,
    val signatureBase64Url: String,
)

/** Exact length-prefixed byte format, suitable for independent Rust/Python producers. */
internal object AirCatCanonicalWire {
    private const val MAX_FIELD_BYTES = 4096
    private const val MAX_MANIFEST_ITEMS = 10000
    private const val MAX_CANONICAL_MANIFEST_BYTES = 8 * 1024 * 1024
    private const val MAX_CANONICAL_GRANT_BYTES = 16 * 1024

    /**
     * Limit bytes *during* canonicalization, not after a huge ByteArray has
     * already been allocated. The on-wire producer has the same 8 MiB budget.
     */
    private class BoundedCanonicalBuffer(private val maxBytes: Int) : ByteArrayOutputStream() {
        override fun write(b: Int) {
            require(count < maxBytes) { "AIRC_CANONICAL_BYTES_EXCEEDED" }
            super.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            require(len >= 0 && off >= 0 && off <= b.size - len && len <= maxBytes - count) {
                "AIRC_CANONICAL_BYTES_EXCEEDED"
            }
            super.write(b, off, len)
        }
    }
    private val hex = "0123456789abcdef".toCharArray()

    private fun DataOutputStream.field(value: String) {
        val b = value.toByteArray(Charsets.UTF_8)
        // Metadata such as artist/album may legitimately be empty. Identities,
        // scopes and paths are checked separately by the caller.
        require(b.size <= MAX_FIELD_BYTES) { "Invalid canonical field length" }
        writeInt(b.size)
        write(b)
    }

    internal fun manifestBytes(manifest: AirCatManifest): ByteArray {
        require(manifest.manifestVersion == 1 && manifest.transferId.isNotBlank() &&
            manifest.sourceDeviceId.isNotBlank())
        require(manifest.items.size <= MAX_MANIFEST_ITEMS)
        // A67/§68.3: an empty catalogue (0 tracks / 0 bytes) is a legitimate transfer
        // that must stay signable. What it must never do is claim a durable completion
        // of zero files, which is enforced at the completion boundary, not here.
        val emptyCatalogue = manifest.items.isEmpty()
        require(
            if (emptyCatalogue) {
                manifest.totalTracks == 0 && manifest.totalBytes == 0L
            } else {
                manifest.totalTracks == manifest.items.size && manifest.totalBytes > 0L
            }
        )
        // The signed total is checked independently of the consumer preflight.
        // Overflows, duplicate logical IDs and malformed remote identifiers fail closed.
        val sum = manifest.items.fold(0L) { total, item ->
            require(item.size > 0)
            Math.addExact(total, item.size)
        }
        require(sum == manifest.totalBytes)
        require(manifest.items.map { it.logicalTrackId }.distinct().size == manifest.items.size)
        require(manifest.items.map { it.remoteTrackId }.distinct().size == manifest.items.size)
        return BoundedCanonicalBuffer(MAX_CANONICAL_MANIFEST_BYTES).also { out ->
            DataOutputStream(out).use { data ->
                data.field("MICHI-AIRCAT-MANIFEST-V1")
                data.writeInt(manifest.manifestVersion)
                data.field(manifest.transferId)
                data.field(manifest.sourceDeviceId)
                data.writeInt(manifest.totalTracks)
                data.writeLong(manifest.totalBytes)
                data.writeInt(manifest.items.size)
                for (item in manifest.items) {
                    require(item.size > 0L && item.logicalTrackId.isNotBlank() &&
                        item.remoteTrackId.isNotBlank() && item.format.isNotBlank())
                    require(item.sha256.matches(Regex("[a-fA-F0-9]{64}")))
                    data.field(item.logicalTrackId)
                    data.field(item.remoteTrackId)
                    data.field(item.title)
                    data.field(item.artist)
                    data.field(item.album)
                    data.field(item.format)
                    data.writeLong(item.size)
                    data.field(item.sha256.lowercase())
                }
            }
        }.toByteArray()
    }

    internal fun manifestSha256(manifest: AirCatManifest): String? = runCatching {
        val hash = MessageDigest.getInstance("SHA-256").digest(manifestBytes(manifest))
        buildString(hash.size * 2) {
            for (b in hash) {
                append(hex[(b.toInt() ushr 4) and 15]); append(hex[b.toInt() and 15])
            }
        }
    }.getOrNull()

    internal fun signedGrantBytes(grant: AirCatSignedGrant): ByteArray =
        BoundedCanonicalBuffer(MAX_CANONICAL_GRANT_BYTES).also { out ->
            DataOutputStream(out).use { data ->
                data.field("MICHI-AIRCAT-GRANT-V1")
                data.writeInt(grant.version)
                data.field(grant.homeId)
                data.field(grant.authorityMichiId)
                data.field(grant.transferId)
                data.field(grant.sourceMichiId)
                data.field(grant.destinationMichiId)
                data.field(grant.sourceLibraryId)
                data.field(grant.destinationLibraryId)
                data.field(grant.selectionScope)
                data.writeInt(grant.scopes.size)
                grant.scopes.forEach { data.run { field(it) } }
                data.field(grant.manifestSha256)
                data.writeLong(grant.issuedAtMillis)
                data.writeLong(grant.expiresAtMillis)
                data.field(grant.nonceBase64Url)
            }
        }.toByteArray()
}

/** Verified in-memory receipt; obtained only after comparing a pinned Home key. */
data class AirCatVerifiedGrant(val transferId: String, val expiresAtMillis: Long, val tlsPin: String)

class AirCatSignedGrantSecurity(private val anchors: AirCatHomeAuthorityProvider) {
    /** Connectivity preflight. Grants are verified independently on every transfer. */
    fun enrolledTlsPin(sourceMichiId: String, baseUrl: String): String? {
        val anchor = anchors.anchorForSource(sourceMichiId) ?: return null
        val ready = sourceMichiId.isNotBlank() && anchor.sourceMichiId == sourceMichiId &&
            // A32: the Home-enrolled endpoint is mandatory: an otherwise valid
            // certificate pin must not be reused against a different host/port.
            anchor.pinnedBaseUrl.isNotBlank() &&
            anchor.pinnedBaseUrl.trimEnd('/') == baseUrl.trimEnd('/') &&
            anchor.homeId == anchor.authorityMichiId &&
            anchors.anchorFor(anchor.homeId)?.let { root ->
                root.homeId == anchor.homeId &&
                    root.authorityMichiId == anchor.authorityMichiId &&
                    root.authorityPublicKeyBase64Url == anchor.authorityPublicKeyBase64Url
            } == true &&
            org.michimusic.link.AirCatTlsPinPolicy.refusal(baseUrl, anchor.tlsSpkiSha256) == null &&
            runCatching {
                val bytes = decoder.decode(anchor.authorityPublicKeyBase64Url)
                bytes.size == 32 && encoder.encodeToString(bytes) == anchor.authorityPublicKeyBase64Url &&
                    deriveMichiId(bytes) == anchor.authorityMichiId
            }.getOrDefault(false)
        return if (ready) anchor.tlsSpkiSha256 else null
    }

    fun enrolledSourceReady(sourceMichiId: String, baseUrl: String): Boolean =
        enrolledTlsPin(sourceMichiId, baseUrl) != null

    private val decoder = Base64.getUrlDecoder()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val requiredScopes = setOf("content:read", "content:transfer")

    fun verify(
        intent: TransferIntent,
        manifest: AirCatManifest,
        grant: AirCatSignedGrant?,
        nowMillis: Long,
    ): Result<AirCatVerifiedGrant> = runCatching {
        val g = grant ?: error("AIRC_GRANT_REQUIRED")
        // Neither a legacy server_id nor an arbitrary device alias is a Michi
        // cryptographic identity. Link IDs and Home IDs are unpadded base64url
        // BLAKE3 digests (43 chars); the grant must use those actual identities.
        val linkIdPattern = Regex("[A-Za-z0-9_-]{43}")
        require(linkIdPattern.matches(g.homeId) &&
            linkIdPattern.matches(g.authorityMichiId) &&
            linkIdPattern.matches(g.sourceMichiId) &&
            linkIdPattern.matches(g.destinationMichiId)) { "AIRC_GRANT_NOT_CANONICAL_MICHI_IDS" }
        require(g.transferId.isNotBlank() && g.sourceLibraryId.isNotBlank() &&
            g.destinationLibraryId.isNotBlank() && g.selectionScope.isNotBlank()) {
            "AIRC_GRANT_REQUIRED_FIELDS"
        }
        require(g.version == 1 && intent.manifestVersion == 1 &&
            manifest.manifestVersion == 1) { "AIRC_GRANT_VERSION" }
        require(g.homeId == g.authorityMichiId) { "AIRC_HOME_ID_NOT_ROOT" }
        require(g.transferId == intent.transferId && g.transferId == manifest.transferId) { "AIRC_GRANT_TRANSFER" }
        require(g.sourceMichiId == intent.sourceMichiId && g.sourceMichiId == manifest.sourceDeviceId &&
            g.destinationMichiId == intent.destinationMichiId &&
            g.sourceLibraryId == intent.sourceLibraryId &&
            g.destinationLibraryId == intent.destinationLibraryId &&
            g.selectionScope == intent.selectionScope) { "AIRC_GRANT_AUDIENCE" }
        // A32: Link v1 and Micro v1 authorize EXACTLY these two scopes.
        // Accepting a signed content:delete or an intent that asks for it would
        // create incompatible authorization semantics across repositories.
        val exactScopes = listOf("content:read", "content:transfer")
        require(g.scopes == exactScopes && intent.requiredScopes == exactScopes &&
            g.scopes.toSet() == requiredScopes) { "AIRC_GRANT_SCOPES" }
        require(g.issuedAtMillis > 0 && nowMillis > 0 &&
            g.expiresAtMillis > g.issuedAtMillis &&
            g.expiresAtMillis - g.issuedAtMillis <= 600_000L &&
            g.issuedAtMillis <= nowMillis + 60_000L && nowMillis < g.expiresAtMillis &&
            intent.expiresAt > nowMillis && g.expiresAtMillis <= intent.expiresAt) { "AIRC_GRANT_EXPIRED" }
        val nonce = decoder.decode(g.nonceBase64Url)
        require(nonce.size in 16..64 && encoder.encodeToString(nonce) == g.nonceBase64Url) {
            "AIRC_GRANT_NONCE"
        }
        // A28: a grant must answer THIS client's unpredictable challenge. A
        // legitimately signed grant from a different authorize transaction is not
        // transferable to this request, even if manifest/peer IDs are identical.
        require(intent.authorizationNonceBase64Url.isNotBlank() &&
            intent.authorizationNonceBase64Url == g.nonceBase64Url) {
            "AIRC_GRANT_REQUEST_NONCE_MISMATCH"
        }
        require(g.manifestSha256.matches(Regex("[a-f0-9]{64}")) &&
            g.manifestSha256 == AirCatCanonicalWire.manifestSha256(manifest)) { "AIRC_GRANT_MANIFEST" }
        // A28: both Home root AND this exact source must be provisioned. A
        // source revoked locally cannot borrow the authority from another peer.
        val anchor = anchors.anchorForSource(g.sourceMichiId)
            ?: error("AIRC_SOURCE_NOT_ENROLLED")
        val homeRoot = anchors.anchorFor(g.homeId) ?: error("AIRC_HOME_NOT_ENROLLED")
        require(homeRoot.homeId == anchor.homeId &&
            homeRoot.authorityMichiId == anchor.authorityMichiId &&
            homeRoot.authorityPublicKeyBase64Url == anchor.authorityPublicKeyBase64Url) {
            "AIRC_HOME_ROOT_MISMATCH"
        }
        require(anchor.homeId == g.homeId &&
            anchor.homeId == anchor.authorityMichiId &&
            anchor.authorityMichiId == g.authorityMichiId &&
            anchor.sourceMichiId == g.sourceMichiId) { "AIRC_HOME_AUTHORITY_MISMATCH" }
        val key = decoder.decode(anchor.authorityPublicKeyBase64Url)
        require(key.size == 32 && encoder.encodeToString(key) == anchor.authorityPublicKeyBase64Url) {
            "AIRC_HOME_AUTHORITY_KEY"
        }
        require(deriveMichiId(key) == g.authorityMichiId) { "AIRC_HOME_KEY_ID_MISMATCH" }
        // The grant is authenticated only with the PINNED Home authority key.
        val signature = decoder.decode(g.signatureBase64Url)
        require(signature.size == 64 && encoder.encodeToString(signature) == g.signatureBase64Url) {
            "AIRC_GRANT_SIGNATURE_FORMAT"
        }
        val bytes = AirCatCanonicalWire.signedGrantBytes(g)
        val valid = Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(key, 0))
            update(bytes, 0, bytes.size)
            verifySignature(signature)
        }
        require(valid) { "AIRC_GRANT_SIGNATURE_INVALID" }
        require(org.michimusic.link.AirCatTlsPinPolicy.refusal(
            "https://example.invalid", anchor.tlsSpkiSha256) == null) { "AIRC_HOME_TLS_PIN_INVALID" }
        AirCatVerifiedGrant(g.transferId, g.expiresAtMillis, anchor.tlsSpkiSha256)
    }

    private fun deriveMichiId(pub: ByteArray): String {
        // Match MichiIdentity and Rust Link (base64url(BLAKE3(public key))).
        val digest = Blake3Digest(256).also { it.update(pub, 0, pub.size) }
        val bytes = ByteArray(32).also { digest.doFinal(it, 0) }
        return encoder.encodeToString(bytes)
    }
}

/** Per-file HTTPS request credential; producer verifies issuer, scopes, nonce and manifest. */
internal fun AirCatSignedGrant.toHttpHeader(): String {
    val json = Json.encodeToString(this)
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    require(encoded.length in 1..8192) { "AIRC_GRANT_HEADER_TOO_LONG" }
    return encoded
}
