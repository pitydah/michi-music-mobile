package org.michimusic.mobile.devices.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.michimusic.link.LinkClient
import org.michimusic.link.identity.MichiSigner
import org.michimusic.mobile.devices.aircat.AirCatHomeAnchor
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** MIM-07: the one lifecycle every Home-authorized operation must consult. */
enum class HomeSessionPhase {
    NOT_ENROLLED,
    ENROLLED_OFFLINE,
    AUTHENTICATING,
    AUTHENTICATED,
    BACKOFF,
    REVOKED,
    TLS_ERROR,
    IDENTITY_MISMATCH,
    ERROR,
}

data class HomeSessionState(
    val phase: HomeSessionPhase = HomeSessionPhase.NOT_ENROLLED,
    val homeId: String? = null,
    val serverMichiId: String? = null,
    val generation: Long = 0L,
    val expiresAtMonotonicMs: Long = 0L,
    val roles: Set<String> = emptySet(),
    val lastError: String? = null,
    val retryAtMonotonicMs: Long = 0L,
)

/**
 * A read-only lease over a client whose `michi_home_*` token was installed by
 * the coordinator only after membership + server-signature verification.
 * The authority owns the client; callers must never close it.
 */
data class HomeSessionLease(
    val client: LinkClient,
    val homeId: String,
    val serverMichiId: String,
    val generation: Long,
    val expiresAtMonotonicMs: Long,
    val roles: Set<String>,
) {
    fun isValid(nowMonotonicMs: Long): Boolean = nowMonotonicMs < expiresAtMonotonicMs
}

class HomeSessionException(val code: String, message: String) : Exception(message)

/**
 * MIM-07: single authority for Home authentication.
 *
 * - One in-flight auth per authority (Mutex); concurrent callers reuse the
 *   published session instead of starting a reauth storm.
 * - The token lives only in the LinkClient's memory and is never written to the
 *   legacy registry or TokenStore.
 * - A monotonic deadline plus a generation counter discards stale responses.
 * - Transient failures get bounded exponential backoff with jitter; identity,
 *   TLS and membership failures do not loop.
 * - MEMBER_REVOKED invalidates the session AND the persisted membership, so a
 *   later call cannot silently resurrect the old grant.
 */
class HomeSessionAuthority(
    private val coordinator: HomeEnrollmentCoordinator,
    private val membershipStore: HomeMembershipPersistence,
    private val identityProvider: suspend () -> MichiSigner?,
    private val clientFactory: (AirCatHomeAnchor, String) -> LinkClient = { anchor, clientMichiId ->
        LinkClient(anchor.pinnedBaseUrl, clientDeviceId = clientMichiId)
            .pinnedAirCatSession(anchor.tlsSpkiSha256)
    },
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val authMutex = Mutex()
    private val generation = AtomicLong(0L)
    private val _state = MutableStateFlow(HomeSessionState())
    val state: StateFlow<HomeSessionState> = _state.asStateFlow()

    @Volatile private var activeAnchor: AirCatHomeAnchor? = null
    @Volatile private var activeClient: LinkClient? = null
    @Volatile private var activeLease: HomeSessionLease? = null
    @Volatile private var revokedHomeIds: Set<String> = emptySet()
    @Volatile private var failedAttempts: Int = 0
    @Volatile private var retryAtMonotonicMs: Long = 0L

    fun currentLease(nowMonotonicMs: Long = monotonicClock()): HomeSessionLease? =
        activeLease?.takeIf { it.isValid(nowMonotonicMs) }

    fun isAuthorized(homeId: String, nowMonotonicMs: Long = monotonicClock()): Boolean =
        currentLease(nowMonotonicMs)?.homeId == homeId

    /** MIM-07: authorization is Home + verified membership roles, never presence. */
    fun authorizedLease(
        homeId: String,
        requiredRoles: Set<String> = emptySet(),
        nowMonotonicMs: Long = monotonicClock(),
    ): HomeSessionLease? = currentLease(nowMonotonicMs)?.takeIf { lease ->
        lease.homeId == homeId && requiredRoles.all { it in lease.roles }
    }

    /**
     * Returns a verified session or a typed failure. A second caller waits for
     * the first auth and then observes the published lease; it does not issue a
     * duplicate challenge.
     */
    suspend fun ensureAuthenticated(anchor: AirCatHomeAnchor): Result<HomeSessionLease> {
        validateAnchor(anchor)?.let { return failure("INVALID_ANCHOR", it) }
        if (anchor.homeId in revokedHomeIds) {
            return failure("MEMBER_REVOKED", "La membresía de este Home fue revocada")
        }
        return withContext(ioDispatcher) {
            authMutex.withLock {
                currentCoroutineContext().ensureActive()
                val now = monotonicClock()
                activeLease?.takeIf {
                    it.homeId == anchor.homeId && it.isValid(now) && activeClient?.isHomeSessionValid(wallClock()) == true
                }?.let { return@withLock Result.success(it) }

                closeActive()
                if (now < retryAtMonotonicMs) {
                    return@withLock failure(
                        "BACKOFF",
                        "Reintento Home en ${retryAtMonotonicMs - now} ms",
                    )
                }
                if (anchor.homeId in revokedHomeIds) {
                    return@withLock failure("MEMBER_REVOKED", "La membresía de este Home fue revocada")
                }

                val requestGeneration = generation.incrementAndGet()
                _state.value = HomeSessionState(
                    phase = HomeSessionPhase.AUTHENTICATING,
                    homeId = anchor.homeId,
                    serverMichiId = anchor.sourceMichiId,
                    generation = requestGeneration,
                )
                val identity = identityProvider()
                    ?: return@withLock failGeneration(
                        requestGeneration, "IDENTITY_UNAVAILABLE", "La identidad local no está disponible",
                        HomeSessionPhase.IDENTITY_MISMATCH, permanent = true,
                    )
                val client = try {
                    clientFactory(anchor, identity.michiId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@withLock failGeneration(
                        requestGeneration, "TLS_TRUST_FAILED",
                        error.message ?: "No se pudo crear el transporte Home",
                        HomeSessionPhase.TLS_ERROR, permanent = true,
                    )
                }

                val outcome = try {
                    coordinator.authenticate(anchor, client)
                } catch (cancelled: CancellationException) {
                    client.close()
                    throw cancelled
                } catch (error: Throwable) {
                    client.close()
                    return@withLock failGeneration(
                        requestGeneration, "NETWORK_ERROR",
                        error.message ?: "Fallo de red durante la autenticación Home",
                        HomeSessionPhase.BACKOFF, permanent = false,
                    )
                }

                if (requestGeneration != generation.get()) {
                    client.close()
                    return@withLock failure("STALE_SESSION", "La respuesta de autenticación quedó obsoleta")
                }
                if (anchor.homeId in revokedHomeIds) {
                    client.close()
                    return@withLock failure("MEMBER_REVOKED", "La membresía de este Home fue revocada")
                }

                when (outcome) {
                    is HomeEnrollmentCoordinator.AuthOutcome.Authenticated -> {
                        if (!client.isHomeSessionValid(wallClock())) {
                            client.close()
                            return@withLock failGeneration(
                                requestGeneration, "TOKEN_NOT_VERIFIED",
                                "El cliente no publicó un token Home verificado",
                                HomeSessionPhase.ERROR, permanent = false,
                            )
                        }
                        val expiresAt = now + outcome.expiresInSeconds * 1_000L
                        val lease = HomeSessionLease(
                            client = client,
                            homeId = anchor.homeId,
                            serverMichiId = anchor.sourceMichiId,
                            generation = requestGeneration,
                            expiresAtMonotonicMs = expiresAt,
                            roles = membershipStore.loadMembership(anchor.homeId)
                                ?.membership?.roles?.toSet().orEmpty(),
                        )
                        activeAnchor = anchor
                        activeClient = client
                        activeLease = lease
                        failedAttempts = 0
                        retryAtMonotonicMs = 0L
                        _state.value = HomeSessionState(
                            phase = HomeSessionPhase.AUTHENTICATED,
                            homeId = anchor.homeId,
                            serverMichiId = anchor.sourceMichiId,
                            generation = requestGeneration,
                            expiresAtMonotonicMs = expiresAt,
                            roles = lease.roles,
                        )
                        Result.success(lease)
                    }

                    is HomeEnrollmentCoordinator.AuthOutcome.Failure -> {
                        client.close()
                        when (outcome.code) {
                            "MEMBER_REVOKED" -> {
                                revokeLocked(anchor.homeId, "La membresía fue revocada por el Home", clearMembership = true)
                                failure("MEMBER_REVOKED", "La membresía de este Home fue revocada")
                            }
                            "NOT_ENROLLED", "NO_PENDING_ENROLLMENT" -> {
                                _state.value = HomeSessionState(
                                    phase = HomeSessionPhase.ENROLLED_OFFLINE,
                                    homeId = anchor.homeId,
                                    serverMichiId = anchor.sourceMichiId,
                                    generation = requestGeneration,
                                    lastError = outcome.message,
                                )
                                failure(outcome.code, outcome.message)
                            }
                            "IDENTITY_CHANGED", "IDENTITY_MISMATCH", "UNTRUSTED_CHALLENGE" -> {
                                _state.value = HomeSessionState(
                                    phase = HomeSessionPhase.IDENTITY_MISMATCH,
                                    homeId = anchor.homeId,
                                    serverMichiId = anchor.sourceMichiId,
                                    generation = requestGeneration,
                                    lastError = outcome.message,
                                )
                                failure(outcome.code, outcome.message)
                            }
                            "TLS_REQUIRED", "TLS_PIN_INVALID", "TLS_TRUST_FAILED" -> {
                                _state.value = HomeSessionState(
                                    phase = HomeSessionPhase.TLS_ERROR,
                                    homeId = anchor.homeId,
                                    serverMichiId = anchor.sourceMichiId,
                                    generation = requestGeneration,
                                    lastError = outcome.message,
                                )
                                failure(outcome.code, outcome.message)
                            }
                            "REVOCATION_STORE_UNAVAILABLE", "NETWORK_ERROR", "BACKOFF" -> {
                                failGeneration(
                                    requestGeneration, outcome.code, outcome.message,
                                    HomeSessionPhase.BACKOFF, permanent = false,
                                )
                            }
                            else -> failGeneration(
                                requestGeneration, outcome.code, outcome.message,
                                HomeSessionPhase.ERROR, permanent = false,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Explicit revocation: cancels reauth, closes transport and wipes membership. */
    fun onMemberRevoked(homeId: String) {
        revoke(homeId, "La membresía fue revocada por el Home", clearMembership = true)
    }

    @Synchronized
    fun invalidate(homeId: String? = null, reason: String = "Sesión Home invalidada", clearMembership: Boolean = false) {
        generation.incrementAndGet()
        closeActive()
        retryAtMonotonicMs = 0L
        failedAttempts = 0
        if (clearMembership && homeId != null) runCatching { membershipStore.clearMembership(homeId) }
        _state.value = HomeSessionState(
            phase = if (clearMembership) HomeSessionPhase.NOT_ENROLLED else HomeSessionPhase.ERROR,
            homeId = homeId ?: _state.value.homeId,
            serverMichiId = _state.value.serverMichiId,
            generation = generation.get(),
            lastError = reason,
        )
    }

    /** Re-enrollment starts a new authority lifecycle only after an explicit QR. */
    @Synchronized
    fun clearRevocation(homeId: String) {
        revokedHomeIds = revokedHomeIds - homeId
        if (_state.value.homeId == homeId && _state.value.phase == HomeSessionPhase.REVOKED) {
            _state.value = _state.value.copy(
                phase = HomeSessionPhase.NOT_ENROLLED,
                lastError = null,
                retryAtMonotonicMs = 0L,
            )
        }
    }

    @Synchronized
    private fun revoke(homeId: String?, reason: String, clearMembership: Boolean) {
        revokeLocked(homeId, reason, clearMembership)
    }

    private fun revokeLocked(homeId: String?, reason: String, clearMembership: Boolean) {
        generation.incrementAndGet()
        closeActive()
        retryAtMonotonicMs = 0L
        failedAttempts = 0
        if (homeId != null) {
            revokedHomeIds = revokedHomeIds + homeId
            if (clearMembership) runCatching { membershipStore.clearMembership(homeId) }
            _state.value = HomeSessionState(
                phase = HomeSessionPhase.REVOKED,
                homeId = homeId,
                serverMichiId = _state.value.serverMichiId,
                generation = generation.get(),
                lastError = reason,
            )
        } else {
            _state.value = HomeSessionState(
                phase = HomeSessionPhase.ERROR,
                generation = generation.get(),
                lastError = reason,
            )
        }
    }

    private fun failGeneration(
        requestGeneration: Long,
        code: String,
        message: String,
        phase: HomeSessionPhase,
        permanent: Boolean,
    ): Result<HomeSessionLease> {
        if (permanent) {
            retryAtMonotonicMs = 0L
        } else {
            failedAttempts = (failedAttempts + 1).coerceAtMost(8)
            retryAtMonotonicMs = monotonicClock() + backoffMs(failedAttempts)
        }
        _state.value = HomeSessionState(
            phase = phase,
            homeId = _state.value.homeId,
            serverMichiId = _state.value.serverMichiId,
            generation = requestGeneration,
            lastError = message,
            retryAtMonotonicMs = retryAtMonotonicMs,
        )
        return failure(code, message)
    }

    private fun backoffMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 5)
        val base = 500L shl exponent
        val capped = base.coerceAtMost(30_000L)
        return capped + random.nextLong(0L, 250L)
    }

    private fun closeActive() {
        activeClient?.close()
        activeClient = null
        activeLease = null
        activeAnchor = null
    }

    private fun validateAnchor(anchor: AirCatHomeAnchor): String? = when {
        anchor.homeId != anchor.authorityMichiId -> "El home_id no coincide con la autoridad"
        !anchor.pinnedBaseUrl.startsWith("https://") -> "Se requiere HTTPS"
        anchor.tlsSpkiSha256.length != 51 || !anchor.tlsSpkiSha256.startsWith("sha256/") ->
            "Pin SPKI inválido"
        else -> null
    }

    private fun failure(code: String, message: String): Result<HomeSessionLease> =
        Result.failure(HomeSessionException(code, message))
}
