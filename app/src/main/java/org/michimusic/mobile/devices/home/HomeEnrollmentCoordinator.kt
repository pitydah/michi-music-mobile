package org.michimusic.mobile.devices.home

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.michimusic.link.LinkClient
import org.michimusic.link.errors.LinkException
import org.michimusic.link.home.HomeAuthSessionRequestDto
import org.michimusic.link.home.HomeAuthTranscript
import org.michimusic.link.home.HomeDeviceAuthChallengeRequestDto
import org.michimusic.link.home.HomeEnrollmentChallengeRequestDto
import org.michimusic.link.home.HomeEnrollmentRequestDto
import org.michimusic.link.home.HomeEnrollmentStatusResponseDto
import org.michimusic.link.home.HomeMembershipDto
import org.michimusic.link.home.HomeMembershipVerifier
import org.michimusic.link.home.HomeRoles
import org.michimusic.link.home.HomeServerSessionVerifier
import org.michimusic.link.home.HomeSessionVerification
import org.michimusic.link.home.HomeVerification
import org.michimusic.link.home.MichiBase64Url
import org.michimusic.link.home.MichiIdentityHash
import org.michimusic.link.identity.MichiSigner
import org.michimusic.mobile.devices.aircat.AirCatHomeAnchor
import java.util.UUID

/**
 * MIM-05/06: authorized Home enrollment and mutual authentication.
 *
 * Security invariants:
 *  - The Home trust anchor (URL, SPKI pin, root public key) comes ONLY from an
 *    operator-confirmed administrative QR already persisted by the AirCat store.
 *  - Every network call runs on an HTTPS client pinned to that anchor.
 *  - A membership certificate is verified against the Home root BEFORE it is
 *    persisted or used; a server session token is published ONLY after the
 *    server membership and `server_signature` verify.
 *  - Admin approval happens in the external WebUI. Mobile never self-enrolls,
 *    never sends an admin password, and never marks itself paired early.
 */
class HomeEnrollmentCoordinator(
    private val identityProvider: suspend () -> MichiSigner?,
    private val membershipStore: HomeMembershipPersistence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val clientFactory: (baseUrl: String, pin: String, clientMichiId: String) -> LinkClient =
        { baseUrl, pin, clientMichiId ->
            LinkClient(baseUrl, clientDeviceId = clientMichiId).pinnedAirCatSession(pin)
        },
) : HomeEnrollmentService {

    sealed interface Outcome {
        data class Pending(val requestId: String, val expiresAt: String) : Outcome
        data class EnrolledOffline(val membership: HomeMembershipDto) : Outcome
        data class Rejected(val status: String) : Outcome
        data class Failure(val code: String, val message: String) : Outcome
    }

    sealed interface AuthOutcome {
        data class Authenticated(val expiresInSeconds: Long) : AuthOutcome
        data class Failure(val code: String, val message: String) : AuthOutcome
    }

    override fun storedMembership(anchor: AirCatHomeAnchor): HomeMembershipDto? {
        val record = membershipStore.loadMembership(anchor.homeId) ?: return null
        if (record.sourceMichiId != anchor.sourceMichiId) return null
        return record.membership.takeIf {
            HomeMembershipVerifier.verify(
                it, anchor.authorityPublicKeyBase64Url, anchor.homeId,
            ) == HomeVerification.Valid
        }
    }

    override fun pendingRequest(anchor: AirCatHomeAnchor): HomeMembershipStore.PendingEnrollment? =
        membershipStore.loadPending(anchor.homeId)?.takeIf { it.sourceMichiId == anchor.sourceMichiId }

    suspend fun requestEnrollment(
        anchor: AirCatHomeAnchor,
        deviceName: String,
    ): Outcome = requestEnrollment(anchor, deviceName, DEFAULT_MOBILE_ROLES)

    /** Step 1-2: challenge + PoP + 202 pending request. Never grants membership. */
    override suspend fun requestEnrollment(
        anchor: AirCatHomeAnchor,
        deviceName: String,
        requestedRoles: List<String>,
    ): Outcome {
        val identity = identityProvider()
            ?: return Outcome.Failure("IDENTITY_UNAVAILABLE", "La identidad local no está disponible")
        val invalid = validateAnchor(anchor) ?: validateDeviceName(deviceName)
            ?: validateRoles(requestedRoles)
        if (invalid != null) return Outcome.Failure("INVALID_ARGUMENT", invalid)
        val client = runCatching { newClient(anchor, identity) }.getOrElse {
            return Outcome.Failure("TLS_PIN_INVALID", it.message ?: "No se pudo crear el transporte Home")
        }
        return try {
            val challenge = client.homeEnrollmentChallenge(
                HomeEnrollmentChallengeRequestDto(
                    homeId = anchor.homeId,
                    clientMichiId = identity.michiId,
                    clientPublicKey = identity.publicKeyBase64Url,
                ),
            ).getOrElse { return failure(it) }
            validateChallenge(challenge.homeId, challenge.serverMichiId, challenge.serverPublicKey,
                challenge.challengeId, challenge.challengeNonce, challenge.expiresIn, 600, anchor)
                ?.let { return Outcome.Failure("UNTRUSTED_CHALLENGE", it) }

            val signature = identity.signCanonicalPayload(
                HomeAuthTranscript.enrollmentPop(
                    homeId = anchor.homeId,
                    serverMichiId = challenge.serverMichiId,
                    clientMichiId = identity.michiId,
                    clientPublicKey = identity.publicKeyBase64Url,
                    challengeId = challenge.challengeId,
                    challengeNonce = challenge.challengeNonce,
                ),
            )
            val response = client.homeEnrollmentRequest(
                HomeEnrollmentRequestDto(
                    homeId = anchor.homeId,
                    challengeId = challenge.challengeId,
                    clientMichiId = identity.michiId,
                    clientPublicKey = identity.publicKeyBase64Url,
                    deviceName = deviceName.trim(),
                    deviceType = "mobile",
                    requestedRoles = requestedRoles.distinct(),
                    clientSignature = signature,
                ),
            ).getOrElse { return failure(it) }

            if (response.homeId != anchor.homeId || response.clientMichiId != identity.michiId) {
                return Outcome.Failure("ENROLLMENT_RESPONSE_MISMATCH", "La respuesta no corresponde a este Home")
            }
            if (response.status != "pending") {
                return Outcome.Rejected(response.status)
            }
            if (!isUuid(response.requestId) || response.claimToken.length !in 16..512) {
                return Outcome.Failure("ENROLLMENT_RESPONSE_INVALID", "Solicitud o claim token inválido")
            }
            val pending = HomeMembershipStore.PendingEnrollment(
                requestId = response.requestId,
                claimToken = response.claimToken,
                homeId = anchor.homeId,
                sourceMichiId = anchor.sourceMichiId,
                deviceName = deviceName.trim(),
                requestedRoles = requestedRoles.distinct(),
                expiresAt = response.expiresAt,
                createdAtMillis = clock(),
            )
            if (!membershipStore.savePending(pending)) {
                return Outcome.Failure("PENDING_PERSISTENCE_FAILED", "No se pudo guardar la solicitud pendiente")
            }
            Outcome.Pending(response.requestId, response.expiresAt)
        } finally {
            client.close()
        }
    }

    suspend fun awaitApproval(
        anchor: AirCatHomeAnchor,
    ): Outcome = awaitApproval(anchor, maxAttempts = 150, intervalMillis = 2_000)

    /** Step 7-8: bounded polling; approval is performed externally by the Home admin. */
    override suspend fun awaitApproval(
        anchor: AirCatHomeAnchor,
        maxAttempts: Int,
        intervalMillis: Long,
    ): Outcome {
        if (maxAttempts < 1) return Outcome.Failure("INVALID_ARGUMENT", "maxAttempts debe ser >= 1")
        var last: Outcome = Outcome.Pending("", "")
        repeat(maxAttempts) {
            currentCoroutineContext().ensureActive()
            last = refreshEnrollment(anchor)
            if (last !is Outcome.Pending) return last
            delay(intervalMillis)
        }
        return last
    }

    /** One idempotent status read; persists a verified membership on approval. */
    override suspend fun refreshEnrollment(anchor: AirCatHomeAnchor): Outcome {
        val identity = identityProvider()
            ?: return Outcome.Failure("IDENTITY_UNAVAILABLE", "La identidad local no está disponible")
        val pending = pendingRequest(anchor)
            ?: return Outcome.Failure("NO_PENDING_ENROLLMENT", "No hay una solicitud pendiente para este Home")
        validateAnchor(anchor)?.let { return Outcome.Failure("INVALID_ANCHOR", it) }
        val client = runCatching { newClient(anchor, identity) }.getOrElse {
            return Outcome.Failure("TLS_PIN_INVALID", it.message ?: "No se pudo crear el transporte Home")
        }
        return try {
            val status = client.homeEnrollmentStatus(pending.requestId, pending.claimToken)
                .getOrElse { error ->
                    if (error is LinkException.ServerError &&
                        (error.code == "ENROLLMENT_NOT_FOUND" || error.code == "NOT_FOUND")) {
                        membershipStore.clearPending(anchor.homeId)
                        return Outcome.Rejected("expired")
                    }
                    return failure(error)
                }
            when (status.status) {
                "pending" -> Outcome.Pending(status.requestId, pending.expiresAt)
                "rejected", "expired" -> {
                    membershipStore.clearPending(anchor.homeId)
                    Outcome.Rejected(status.status)
                }
                "approved" -> verifyAndStore(anchor, identity, status)
                else -> Outcome.Failure("UNKNOWN_ENROLLMENT_STATUS", "Estado de enrollment no reconocido")
            }
        } finally {
            client.close()
        }
    }

    /**
     * Step 9-12. The caller supplies the pinned client whose lifetime will hold
     * the session token; the token is published only after full verification.
     */
    suspend fun authenticate(
        anchor: AirCatHomeAnchor,
        client: LinkClient,
    ): AuthOutcome {
        val identity = identityProvider()
            ?: return AuthOutcome.Failure("IDENTITY_UNAVAILABLE", "La identidad local no está disponible")
        validateAnchor(anchor)?.let { return AuthOutcome.Failure("INVALID_ANCHOR", it) }
        val membership = storedMembership(anchor)
            ?: return AuthOutcome.Failure("NOT_ENROLLED", "No hay una membresía verificada para este Home")
        if (membership.deviceMichiId != identity.michiId ||
            membership.devicePublicKey != identity.publicKeyBase64Url) {
            return AuthOutcome.Failure("IDENTITY_CHANGED", "La membresía pertenece a otra identidad local")
        }
        val challenge = client.homeAuthChallenge(
            HomeDeviceAuthChallengeRequestDto(
                clientMichiId = identity.michiId,
                clientPublicKey = identity.publicKeyBase64Url,
                homeId = anchor.homeId,
            ),
        ).getOrElse { return authFailure(it) }
        validateChallenge(null, challenge.serverMichiId, challenge.serverPublicKey,
            challenge.challengeId, challenge.challengeNonce, challenge.expiresIn, 300, anchor)
            ?.let { return AuthOutcome.Failure("UNTRUSTED_CHALLENGE", it) }

        val signature = identity.signCanonicalPayload(
            HomeAuthTranscript.deviceAuth(
                homeId = anchor.homeId,
                serverMichiId = challenge.serverMichiId,
                clientMichiId = identity.michiId,
                challengeId = challenge.challengeId,
                challengeNonce = challenge.challengeNonce,
            ),
        )
        val response = client.homeAuthSession(
            HomeAuthSessionRequestDto(
                challengeId = challenge.challengeId,
                clientMichiId = identity.michiId,
                membership = membership,
                clientSignature = signature,
            ),
        ).getOrElse { return authFailure(it) }

        when (val verification = HomeServerSessionVerifier.verify(
            response = response,
            homeRootPublicKeyBase64Url = anchor.authorityPublicKeyBase64Url,
            expectedHomeId = anchor.homeId,
            expectedServerMichiId = challenge.serverMichiId,
            clientMichiId = identity.michiId,
            challengeId = challenge.challengeId,
        )) {
            is HomeSessionVerification.Invalid ->
                return AuthOutcome.Failure("SERVER_AUTH_REJECTED", verification.reason)
            is HomeSessionVerification.Verified -> {
                client.acceptVerifiedHomeSession(verification.session, clock())
                return AuthOutcome.Authenticated(response.expiresIn)
            }
        }
    }

    private fun verifyAndStore(
        anchor: AirCatHomeAnchor,
        identity: MichiSigner,
        status: HomeEnrollmentStatusResponseDto,
    ): Outcome {
        if (status.homeId != anchor.homeId) {
            return Outcome.Failure("HOME_MISMATCH", "El estado pertenece a otro Home")
        }
        val rootKey = status.homeRootPublicKey
            ?: return Outcome.Failure("MISSING_ROOT_KEY", "El servidor no envió la clave raíz")
        if (rootKey != anchor.authorityPublicKeyBase64Url) {
            return Outcome.Failure("ROOT_KEY_CHANGED", "La raíz Home cambió; se requiere un QR administrativo nuevo")
        }
        val membership = status.membership
            ?: return Outcome.Failure("MISSING_MEMBERSHIP", "Aprobado sin certificado de membresía")
        if (membership.deviceMichiId != identity.michiId ||
            membership.devicePublicKey != identity.publicKeyBase64Url) {
            return Outcome.Failure("IDENTITY_MISMATCH", "La membresía no corresponde a esta identidad")
        }
        when (val verified = HomeMembershipVerifier.verify(membership, rootKey, anchor.homeId)) {
            HomeVerification.Valid -> Unit
            is HomeVerification.Invalid ->
                return Outcome.Failure("INVALID_MEMBERSHIP", verified.reason)
        }
        val record = HomeMembershipStore.MembershipRecord(
            homeId = anchor.homeId,
            sourceMichiId = anchor.sourceMichiId,
            membership = membership,
            storedAtMillis = clock(),
        )
        if (!membershipStore.saveMembership(record)) {
            return Outcome.Failure("MEMBERSHIP_PERSISTENCE_FAILED", "No se pudo guardar la membresía")
        }
        membershipStore.clearPending(anchor.homeId)
        return Outcome.EnrolledOffline(membership)
    }

    private fun newClient(anchor: AirCatHomeAnchor, identity: MichiSigner): LinkClient =
        clientFactory(anchor.pinnedBaseUrl, anchor.tlsSpkiSha256, identity.michiId)

    private fun validateAnchor(anchor: AirCatHomeAnchor): String? = when {
        !MichiIdentityHash.isMichiId(anchor.homeId) -> "home_id inválido"
        !MichiIdentityHash.isMichiId(anchor.sourceMichiId) -> "origen inválido"
        anchor.authorityMichiId != anchor.homeId -> "autoridad y home_id no coinciden"
        MichiIdentityHash.deriveMichiId(anchor.authorityPublicKeyBase64Url) != anchor.homeId ->
            "la clave raíz no deriva del home_id"
        anchor.authorityPublicKeyBase64Url.length != 43 ||
            MichiBase64Url.decodeExactly(anchor.authorityPublicKeyBase64Url, 32) == null ->
            "clave raíz inválida"
        !anchor.pinnedBaseUrl.startsWith("https://") -> "se requiere HTTPS"
        anchor.tlsSpkiSha256.length != 51 || !anchor.tlsSpkiSha256.startsWith("sha256/") ->
            "pin SPKI inválido"
        else -> null
    }

    private fun validateDeviceName(name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "nombre de dispositivo requerido"
            trimmed.length > 128 -> "nombre de dispositivo demasiado largo"
            trimmed.any { it.code < 0x20 } -> "nombre de dispositivo contiene controles"
            else -> null
        }
    }

    private fun validateRoles(roles: List<String>): String? = when {
        roles.isEmpty() -> "se requiere al menos un rol"
        roles.distinct().size != roles.size -> "roles duplicados"
        roles.any { it !in HomeRoles.CANONICAL } -> "rol no canónico"
        else -> null
    }

    private fun validateChallenge(
        homeId: String?,
        serverMichiId: String,
        serverPublicKey: String,
        challengeId: String,
        nonce: String,
        expiresIn: Int,
        maxExpiry: Int,
        anchor: AirCatHomeAnchor,
    ): String? = when {
        homeId != null && homeId != anchor.homeId -> "home_id no coincide con el ancla"
        serverMichiId != anchor.sourceMichiId -> "servidor no coincide con el ancla"
        MichiIdentityHash.deriveMichiId(serverPublicKey) != serverMichiId -> "clave del servidor incoherente"
        !isUuid(challengeId) -> "challenge_id inválido"
        nonce.length !in 22..86 || MichiBase64Url.decode(nonce) == null -> "nonce inválido"
        expiresIn !in 1..maxExpiry -> "expiración de challenge inválida"
        else -> null
    }

    private fun isUuid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)

    private fun failure(error: Throwable): Outcome.Failure {
        val code = when (error) {
            is LinkException.MemberRevoked -> "MEMBER_REVOKED"
            is LinkException.HomeSessionRequired -> "HOME_SESSION_REQUIRED"
            is LinkException.TlsRequired -> "TLS_REQUIRED"
            is LinkException.RevocationStoreUnavailable -> "REVOCATION_STORE_UNAVAILABLE"
            is LinkException.MembershipRejected -> error.code
            is LinkException.ServerError -> error.code
            is LinkException.Unauthorized -> "UNAUTHORIZED"
            is LinkException.Forbidden -> "FORBIDDEN"
            is LinkException.QueueDegraded -> "QUEUE_DEGRADED"
            else -> "NETWORK_ERROR"
        }
        return Outcome.Failure(code, error.message ?: "Fallo de enrollment")
    }

    private fun authFailure(error: Throwable): AuthOutcome.Failure {
        val mapped = failure(error)
        return AuthOutcome.Failure(mapped.code, mapped.message)
    }

    companion object {
        val DEFAULT_MOBILE_ROLES = listOf("mobile_player", "remote_controller", "sync_client")
    }
}
