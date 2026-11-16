package org.michimusic.mobile.devices.aircat

import java.util.Base64
import org.bouncycastle.crypto.digests.Blake3Digest
import org.michimusic.link.AirCatTlsPinPolicy
import kotlinx.serialization.Serializable

/**
 * A28: physical out-of-band admin QR, NOT a server-info/discovery response.
 * The controller MUST explicitly confirm a trusted, independently displayed QR.
 * This payload is not itself authenticated merely because its key hashes match:
 * physical provenance and user confirmation are the trust bootstrap.
 */
@Serializable
data class AirCatTrustedEnrollmentQr(
    val type: String,
    val homeId: String,
    val authorityMichiId: String,
    val authorityPublicKeyBase64Url: String,
    val sourceMichiId: String,
    val pinnedBaseUrl: String,
    val tlsSpkiSha256: String,
    val nonceBase64Url: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
)

object AirCatTrustedEnrollmentPolicy {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    private val id = Regex("[A-Za-z0-9_-]{43}")

    fun validate(qr: AirCatTrustedEnrollmentQr, now: Long): AirCatHomeAnchor? = runCatching {
        require(qr.type == "michi.aircat.home.enrollment.v1")
        require(id.matches(qr.homeId) && id.matches(qr.authorityMichiId) && id.matches(qr.sourceMichiId))
        require(qr.issuedAtMillis > 0 && qr.expiresAtMillis > qr.issuedAtMillis &&
            qr.expiresAtMillis - qr.issuedAtMillis <= 600_000 &&
            qr.issuedAtMillis <= now + 60_000 && now < qr.expiresAtMillis)
        val pk = dec.decode(qr.authorityPublicKeyBase64Url)
        require(pk.size == 32 && enc.encodeToString(pk) == qr.authorityPublicKeyBase64Url)
        val digest = Blake3Digest(256).also { it.update(pk, 0, pk.size) }
        val hashed = ByteArray(32).also { digest.doFinal(it, 0) }
        val rootId = enc.encodeToString(hashed)
        // Micro's HomeRootAuthority.home_id is BLAKE3(root Ed25519 key).
        require(rootId == qr.homeId && rootId == qr.authorityMichiId)
        val nonce = dec.decode(qr.nonceBase64Url)
        require(nonce.size in 16..64 && enc.encodeToString(nonce) == qr.nonceBase64Url)
        require(AirCatTlsPinPolicy.refusal(qr.pinnedBaseUrl, qr.tlsSpkiSha256) == null)
        val url = java.net.URI(qr.pinnedBaseUrl)
        require(url.rawPath.isNullOrEmpty() || url.rawPath == "/")
        AirCatHomeAnchor(qr.homeId, rootId, qr.authorityPublicKeyBase64Url,
            qr.sourceMichiId, qr.tlsSpkiSha256, qr.pinnedBaseUrl.trimEnd('/'))
    }.getOrNull()
}
