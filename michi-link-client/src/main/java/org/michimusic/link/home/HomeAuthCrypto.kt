package org.michimusic.link.home

import org.bouncycastle.crypto.digests.Blake3Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.time.OffsetDateTime
import java.util.Base64

/**
 * MIM-02: strict, canonical primitives for Michi Home identity and mutual auth.
 *
 * The normative source is `pitydah/michi-link` (`crates/michi-identity/src/home.rs`
 * and `tests/vectors/interop/micro-mobile-interop-vectors.json`, pinned by Micro
 * Server at submodule 8dad0fb5). The canonical payloads are direct byte
 * concatenations with NO separators or newlines; the Rust domain strings are
 * `michi-link-*`, not the `michi-home-*` spellings used by some handoff prose.
 */
object MichiBase64Url {
    private val alphabet = Regex("^[A-Za-z0-9_-]+$")

    fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * Rejects padding, standard-base64 characters, whitespace and non-canonical
     * trailing bits. A value decoded through this gate always re-encodes to the
     * exact same string.
     */
    fun decode(value: String): ByteArray? {
        if (value.isEmpty() || !alphabet.matches(value)) return null
        if (value.length % 4 == 1) return null
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull() ?: return null
        return decoded.takeIf { encode(it) == value }
    }

    fun decodeExactly(value: String, size: Int): ByteArray? =
        decode(value)?.takeIf { it.size == size }
}

object MichiIdentityHash {
    const val SCHEME = "ed25519-blake3-v1"

    /** `base64urlNoPad(BLAKE3-256(raw 32-byte Ed25519 public key))`. */
    fun deriveMichiId(publicKeyBase64Url: String): String? {
        val publicKey = MichiBase64Url.decodeExactly(publicKeyBase64Url, 32) ?: return null
        return MichiBase64Url.encode(blake3(publicKey))
    }

    fun blake3(bytes: ByteArray): ByteArray {
        val digest = Blake3Digest(256)
        digest.update(bytes, 0, bytes.size)
        return ByteArray(32).also { digest.doFinal(it, 0) }
    }

    fun isMichiId(value: String): Boolean =
        value.length == 43 && MichiBase64Url.decodeExactly(value, 32) != null
}

/** Exact domain-separated transcripts signed by Home peers (Michi Link v1). */
object HomeAuthTranscript {
    const val ENROLLMENT_POP_DOMAIN = "michi-link-home-enrollment-pop-v1"
    const val DEVICE_AUTH_DOMAIN = "michi-link-device-auth-v1"
    const val SERVER_AUTH_DOMAIN = "michi-link-server-auth-v1"
    const val MEMBERSHIP_DOMAIN = "michi-link-membership-v1"
    const val REVOCATION_DOMAIN = "michi-link-revocation-v1"

    fun enrollmentPop(
        homeId: String,
        serverMichiId: String,
        clientMichiId: String,
        clientPublicKey: String,
        challengeId: String,
        challengeNonce: String,
    ): ByteArray = concat(
        ENROLLMENT_POP_DOMAIN, homeId, serverMichiId, clientMichiId,
        clientPublicKey, challengeId, challengeNonce,
    )

    fun deviceAuth(
        homeId: String,
        serverMichiId: String,
        clientMichiId: String,
        challengeId: String,
        challengeNonce: String,
    ): ByteArray = concat(
        DEVICE_AUTH_DOMAIN, homeId, serverMichiId, clientMichiId,
        challengeId, challengeNonce,
    )

    fun serverAuth(
        homeId: String,
        serverMichiId: String,
        clientMichiId: String,
        challengeId: String,
        sessionToken: String,
    ): ByteArray = concat(
        SERVER_AUTH_DOMAIN, homeId, serverMichiId, clientMichiId,
        challengeId, sessionToken,
    )

    /**
     * Rust `canonical_membership_bytes`: domain || home || device || public key
     * || type, then one `:` + role per SORTED role, then `:` + issued_at + `:`
     * + decimal serial. The certificate has no `expires_at` field.
     */
    fun membership(
        homeId: String,
        deviceMichiId: String,
        devicePublicKey: String,
        deviceType: String,
        roles: List<String>,
        issuedAt: String,
        serial: Long,
    ): ByteArray {
        require(serial >= 1) { "membership serial must be >= 1" }
        val out = ArrayList<ByteArray>()
        out += MEMBERSHIP_DOMAIN.toByteArray(Charsets.UTF_8)
        out += homeId.toByteArray(Charsets.UTF_8)
        out += deviceMichiId.toByteArray(Charsets.UTF_8)
        out += devicePublicKey.toByteArray(Charsets.UTF_8)
        out += deviceType.toByteArray(Charsets.UTF_8)
        roles.map { it }.sorted().forEach { role ->
            out += ":".toByteArray(Charsets.UTF_8)
            out += role.toByteArray(Charsets.UTF_8)
        }
        out += ":".toByteArray(Charsets.UTF_8)
        out += issuedAt.toByteArray(Charsets.UTF_8)
        out += ":".toByteArray(Charsets.UTF_8)
        out += serial.toString().toByteArray(Charsets.UTF_8)
        return concatBytes(out)
    }

    fun revocation(
        homeId: String,
        revokedDeviceMichiId: String,
        revokedAt: String,
        reason: String,
    ): ByteArray = concat(
        REVOCATION_DOMAIN, homeId, revokedDeviceMichiId, ":$revokedAt:$reason",
    )

    private fun concat(vararg values: String): ByteArray =
        concatBytes(values.map { it.toByteArray(Charsets.UTF_8) })

    private fun concatBytes(parts: List<ByteArray>): ByteArray {
        val size = parts.sumOf { it.size }
        val out = ByteArray(size)
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }
}

/** Raw Ed25519 sign/verify with strict wire-shape validation. */
object HomeEd25519 {
    fun sign(privateKey: Ed25519PrivateKeyParameters, payload: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(payload, 0, payload.size)
        return MichiBase64Url.encode(signer.generateSignature())
    }

    fun verify(publicKeyBase64Url: String, signatureBase64Url: String, payload: ByteArray): Boolean {
        val publicKey = MichiBase64Url.decodeExactly(publicKeyBase64Url, 32) ?: return false
        val signature = MichiBase64Url.decodeExactly(signatureBase64Url, 64) ?: return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(payload, 0, payload.size)
        return verifier.verifySignature(signature)
    }
}

/** Canonical roles accepted by Link `michi-membership.schema.json`. */
object HomeRoles {
    val CANONICAL = setOf(
        "desktop_player", "library_master", "sync_host", "music_server",
        "library_host", "playback_host", "mobile_player", "remote_controller",
        "sync_client", "audio_receiver",
    )
}

/** Device types accepted by the Link membership schema plus Micro's `desktop`. */
object HomeDeviceTypes {
    val CANONICAL = setOf("player", "server", "stream", "mobile")

    /**
     * Micro Server's enrollment endpoint also accepts `desktop` and can issue a
     * signed membership with it even though the Link membership schema omits it.
     * Treat the signed certificate as authoritative but keep the divergence
     * visible in `ML-`/`MS-` tickets instead of rejecting a root-signed member.
     */
    fun isAccepted(value: String): Boolean = value in CANONICAL || value == "desktop"
}

object HomeTime {
    fun isRfc3339(value: String): Boolean =
        runCatching { OffsetDateTime.parse(value) }.isSuccess
}

sealed interface HomeVerification {
    data object Valid : HomeVerification
    data class Invalid(val reason: String) : HomeVerification
}

/**
 * MIM-06: verify the device membership certificate BEFORE it can authorize a
 * session. A revocation entry can only deny; it is never allowed to grant.
 */
object HomeMembershipVerifier {
    fun verify(
        membership: HomeMembershipDto,
        homeRootPublicKeyBase64Url: String,
        expectedHomeId: String,
        revokedDeviceMichiIds: Set<String> = emptySet(),
    ): HomeVerification {
        if (membership.version != 1) return HomeVerification.Invalid("UNSUPPORTED_MEMBERSHIP_VERSION")
        if (membership.homeId != expectedHomeId) return HomeVerification.Invalid("HOME_ID_MISMATCH")
        if (!MichiIdentityHash.isMichiId(membership.deviceMichiId)) {
            return HomeVerification.Invalid("INVALID_DEVICE_MICHI_ID")
        }
        if (MichiBase64Url.decodeExactly(membership.devicePublicKey, 32) == null) {
            return HomeVerification.Invalid("INVALID_DEVICE_PUBLIC_KEY")
        }
        if (MichiIdentityHash.deriveMichiId(membership.devicePublicKey) != membership.deviceMichiId) {
            return HomeVerification.Invalid("IDENTITY_MISMATCH")
        }
        if (!HomeDeviceTypes.isAccepted(membership.deviceType)) {
            return HomeVerification.Invalid("UNSUPPORTED_DEVICE_TYPE")
        }
        if (membership.roles.isEmpty() || membership.roles.distinct().size != membership.roles.size) {
            return HomeVerification.Invalid("INVALID_ROLES")
        }
        if (membership.roles.any { it !in HomeRoles.CANONICAL }) {
            return HomeVerification.Invalid("INVALID_ROLES")
        }
        if (!HomeTime.isRfc3339(membership.issuedAt)) {
            return HomeVerification.Invalid("INVALID_ISSUED_AT")
        }
        if (membership.serial < 1) return HomeVerification.Invalid("INVALID_SERIAL")
        if (membership.deviceMichiId in revokedDeviceMichiIds) {
            return HomeVerification.Invalid("MEMBER_REVOKED")
        }
        val payload = HomeAuthTranscript.membership(
            homeId = membership.homeId,
            deviceMichiId = membership.deviceMichiId,
            devicePublicKey = membership.devicePublicKey,
            deviceType = membership.deviceType,
            roles = membership.roles,
            issuedAt = membership.issuedAt,
            serial = membership.serial,
        )
        if (!HomeEd25519.verify(homeRootPublicKeyBase64Url, membership.signature, payload)) {
            return HomeVerification.Invalid("INVALID_MEMBERSHIP_SIGNATURE")
        }
        return HomeVerification.Valid
    }
}

object HomeServerSessionVerifier {
    /**
     * Client MUST verify the server membership against the Home root AND the
     * server confirmation signature over the exact transcript before publishing
     * any `michi_home_*` token.
     */
    fun verify(
        response: HomeAuthSessionResponseDto,
        homeRootPublicKeyBase64Url: String,
        expectedHomeId: String,
        expectedServerMichiId: String,
        clientMichiId: String,
        challengeId: String,
        revokedDeviceMichiIds: Set<String> = emptySet(),
    ): HomeSessionVerification {
        if (response.tokenType != "Bearer") return HomeSessionVerification.Invalid("INVALID_TOKEN_TYPE")
        if (!response.sessionToken.startsWith("michi_home_") ||
            response.sessionToken.length !in 16..512) {
            return HomeSessionVerification.Invalid("INVALID_SESSION_TOKEN")
        }
        if (response.expiresIn !in 1..86_400) {
            return HomeSessionVerification.Invalid("INVALID_EXPIRES_IN")
        }
        if (response.serverMichiId != expectedServerMichiId) {
            return HomeSessionVerification.Invalid("UNEXPECTED_SERVER")
        }
        val membership = response.serverMembership
        val membershipResult = HomeMembershipVerifier.verify(
            membership, homeRootPublicKeyBase64Url, expectedHomeId, revokedDeviceMichiIds,
        )
        if (membershipResult is HomeVerification.Invalid) {
            return HomeSessionVerification.Invalid(membershipResult.reason)
        }
        if (membership.deviceMichiId != response.serverMichiId) {
            return HomeSessionVerification.Invalid("SERVER_MEMBERSHIP_MISMATCH")
        }
        val payload = HomeAuthTranscript.serverAuth(
            homeId = expectedHomeId,
            serverMichiId = expectedServerMichiId,
            clientMichiId = clientMichiId,
            challengeId = challengeId,
            sessionToken = response.sessionToken,
        )
        if (!HomeEd25519.verify(membership.devicePublicKey, response.serverSignature, payload)) {
            return HomeSessionVerification.Invalid("INVALID_SERVER_SIGNATURE")
        }
        return HomeSessionVerification.Verified(
            VerifiedHomeSession(
                homeId = expectedHomeId,
                serverMichiId = expectedServerMichiId,
                clientMichiId = clientMichiId,
                sessionToken = response.sessionToken,
                expiresInSeconds = response.expiresIn,
                challengeId = challengeId,
                serverMembership = membership,
            ),
        )
    }
}
