package org.michimusic.mobile.devices.aircat.experience

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.michimusic.link.home.HomeEnrollmentPhase
import org.michimusic.mobile.devices.aircat.AirCatHomeTrustAdmin
import org.michimusic.mobile.devices.home.HomeEnrollmentCoordinator
import org.michimusic.mobile.devices.home.HomeEnrollmentService
import org.michimusic.mobile.devices.home.HomeSessionAuthority
import org.michimusic.mobile.devices.home.HomeSessionException

/**
 * A71-09: owns every transition of the trust screen.
 *
 * The composable never reads the Keystore and never decides that a revocation
 * succeeded; it renders [state] and emits [MichiLinkTrustIntent]. A confirmation
 * always carries the exact snapshot it displayed, and a second confirmation
 * while one is in flight is ignored so a double tap cannot issue two destructive
 * commits.
 */
internal class MichiLinkTrustController(
    private val scope: CoroutineScope,
    private val admin: MichiLinkTrustAdmin,
    private val homeTrust: AirCatHomeTrustAdmin? = null,
    private val enrollment: HomeEnrollmentService? = null,
    private val sessionAuthority: HomeSessionAuthority? = null,
) {

    private val _state = MutableStateFlow(
        MichiLinkTrustState(enrollmentAvailable = enrollment != null && homeTrust != null),
    )
    val state: StateFlow<MichiLinkTrustState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var enrollmentJob: Job? = null
    private var loadToken = 0

    fun dispatch(intent: MichiLinkTrustIntent) {
        when (intent) {
            MichiLinkTrustIntent.Refresh -> if (!_state.value.busy) load()

            is MichiLinkTrustIntent.RequestSourceRevocation -> if (_state.value.canRequest) {
                _state.value = _state.value.copy(
                    pending = MichiLinkTrustPending.Source(intent.source),
                    notice = null,
                )
            }

            is MichiLinkTrustIntent.RequestHomeRevocation -> if (_state.value.canRequest) {
                _state.value = _state.value.copy(
                    pending = MichiLinkTrustPending.Home(intent.home),
                    notice = null,
                )
            }

            MichiLinkTrustIntent.ConfirmRevocation -> confirm()
            MichiLinkTrustIntent.CancelRevocation ->
                _state.value = _state.value.copy(pending = null)
            MichiLinkTrustIntent.DismissNotice ->
                _state.value = _state.value.copy(notice = null)

            is MichiLinkTrustIntent.RequestEnrollment -> requestEnrollment(intent.source)
            MichiLinkTrustIntent.RefreshEnrollment -> refreshEnrollment()
            MichiLinkTrustIntent.PollEnrollment -> pollEnrollment()
            MichiLinkTrustIntent.AuthenticateEnrollment -> authenticate()
            MichiLinkTrustIntent.CancelEnrollment -> {
                enrollmentJob?.cancel()
                _state.value = _state.value.copy(
                    busy = false,
                    enrollment = _state.value.enrollment.copy(busy = false),
                )
            }
        }
    }

    private fun activeAnchor(): org.michimusic.mobile.devices.aircat.AirCatHomeAnchor? {
        val sourceId = _state.value.enrollment.sourceMichiId
            ?: _state.value.sources.firstOrNull()?.sourceMichiId
            ?: return null
        return homeTrust?.anchorForSource(sourceId)
    }

    private fun requestEnrollment(source: org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeSource) {
        if (!_state.value.canEnroll || _state.value.busy) return
        val anchor = homeTrust?.anchorForSource(source.sourceMichiId) ?: run {
            _state.value = _state.value.copy(
                enrollment = HomeEnrollmentUiState(
                    sourceMichiId = source.sourceMichiId,
                    phase = HomeEnrollmentPhase.ERROR,
                    message = "El ancla Home local ya no existe; volvé a escanear el QR administrativo",
                ),
            )
            return
        }
        enrollmentJob?.cancel()
        _state.value = _state.value.copy(
            busy = true,
            enrollment = HomeEnrollmentUiState(
                sourceMichiId = source.sourceMichiId,
                phase = HomeEnrollmentPhase.ENROLLMENT_PENDING,
                busy = true,
                message = "Solicitando membresía…",
            ),
        )
        enrollmentJob = scope.launch {
            val outcome = enrollment!!.requestEnrollment(anchor, "Michi Music Mobile")
            _state.value = _state.value.copy(
                busy = false,
                enrollment = outcome.toUi(source.sourceMichiId),
            )
        }
    }

    private fun refreshEnrollment() {
        if (!_state.value.canEnroll || _state.value.busy) return
        val anchor = activeAnchor() ?: return
        val sourceId = anchor.sourceMichiId
        enrollmentJob?.cancel()
        _state.value = _state.value.copy(
            busy = true,
            enrollment = _state.value.enrollment.copy(busy = true, message = "Consultando aprobación…"),
        )
        enrollmentJob = scope.launch {
            val outcome = enrollment!!.refreshEnrollment(anchor)
            if (outcome is HomeEnrollmentCoordinator.Outcome.EnrolledOffline) {
                sessionAuthority?.clearRevocation(anchor.homeId)
            }
            _state.value = _state.value.copy(
                busy = false,
                enrollment = outcome.toUi(sourceId),
            )
        }
    }

    private fun pollEnrollment() {
        if (!_state.value.canEnroll || _state.value.busy) return
        val anchor = activeAnchor() ?: return
        val sourceId = anchor.sourceMichiId
        enrollmentJob?.cancel()
        _state.value = _state.value.copy(
            busy = true,
            enrollment = _state.value.enrollment.copy(
                busy = true,
                phase = HomeEnrollmentPhase.ENROLLMENT_PENDING,
                message = "Esperando aprobación del administrador…",
            ),
        )
        enrollmentJob = scope.launch {
            val outcome = enrollment!!.awaitApproval(anchor, maxAttempts = 15, intervalMillis = 2_000)
            if (outcome is HomeEnrollmentCoordinator.Outcome.EnrolledOffline) {
                sessionAuthority?.clearRevocation(anchor.homeId)
            }
            _state.value = _state.value.copy(
                busy = false,
                enrollment = outcome.toUi(sourceId),
            )
        }
    }

    private fun authenticate() {
        if (!_state.value.canEnroll || _state.value.busy) return
        val anchor = activeAnchor() ?: return
        val sourceId = anchor.sourceMichiId
        val authority = sessionAuthority ?: return
        enrollmentJob?.cancel()
        _state.value = _state.value.copy(
            busy = true,
            enrollment = _state.value.enrollment.copy(
                busy = true,
                phase = HomeEnrollmentPhase.ENROLLED_OFFLINE,
                message = "Autenticando sesión Home…",
            ),
        )
        enrollmentJob = scope.launch {
            val result = authority.ensureAuthenticated(anchor)
            val error = result.exceptionOrNull()
            _state.value = _state.value.copy(
                busy = false,
                enrollment = if (result.isSuccess) {
                    HomeEnrollmentUiState(
                        sourceMichiId = sourceId,
                        phase = HomeEnrollmentPhase.AUTHENTICATED_ONLINE,
                        message = "Sesión Home verificada en memoria",
                    )
                } else {
                    HomeEnrollmentUiState(
                        sourceMichiId = sourceId,
                        phase = when ((error as? HomeSessionException)?.code) {
                            "MEMBER_REVOKED" -> HomeEnrollmentPhase.REVOKED
                            "TLS_REQUIRED", "TLS_PIN_INVALID", "TLS_TRUST_FAILED" -> HomeEnrollmentPhase.ERROR
                            "IDENTITY_CHANGED", "IDENTITY_MISMATCH" -> HomeEnrollmentPhase.ERROR
                            else -> HomeEnrollmentPhase.ENROLLED_OFFLINE
                        },
                        message = error?.message ?: "No se pudo autenticar la sesión Home",
                    )
                },
            )
        }
    }

    private fun HomeEnrollmentCoordinator.Outcome.toUi(sourceId: String): HomeEnrollmentUiState = when (this) {
        is HomeEnrollmentCoordinator.Outcome.Pending -> HomeEnrollmentUiState(
            sourceMichiId = sourceId,
            phase = HomeEnrollmentPhase.ENROLLMENT_PENDING,
            requestId = requestId,
            message = "Solicitud enviada. Aprobala desde la WebUI del Home.",
        )
        is HomeEnrollmentCoordinator.Outcome.EnrolledOffline -> HomeEnrollmentUiState(
            sourceMichiId = sourceId,
            phase = HomeEnrollmentPhase.ENROLLED_OFFLINE,
            message = "Membresía verificada y guardada; falta autenticar la sesión",
        )
        is HomeEnrollmentCoordinator.Outcome.Rejected -> HomeEnrollmentUiState(
            sourceMichiId = sourceId,
            phase = HomeEnrollmentPhase.REJECTED,
            message = "El Home rechazó o expiró la solicitud ($status)",
        )
        is HomeEnrollmentCoordinator.Outcome.Failure -> HomeEnrollmentUiState(
            sourceMichiId = sourceId,
            phase = when (code) {
                "MEMBER_REVOKED" -> HomeEnrollmentPhase.REVOKED
                "TLS_PIN_INVALID" -> HomeEnrollmentPhase.ERROR
                else -> HomeEnrollmentPhase.ERROR
            },
            message = "$code: $message",
        )
    }

    private fun load() {
        loadJob?.cancel()
        val token = ++loadToken
        _state.value = _state.value.copy(loading = true, loadFailure = null)
        loadJob = scope.launch {
            val result = admin.load()
            if (token != loadToken) return@launch
            _state.value = result.fold(
                onSuccess = { inventory ->
                    _state.value.copy(
                        loading = false,
                        loadFailure = null,
                        homes = inventory.homes,
                        sources = inventory.sources,
                    )
                },
                onFailure = {
                    _state.value.copy(
                        loading = false,
                        loadFailure = MichiLinkTrustLoadFailure.KEYSTORE_UNAVAILABLE,
                        homes = emptyList(),
                        sources = emptyList(),
                        pending = null,
                    )
                },
            )
        }
    }

    private fun confirm() {
        val pending = _state.value.pending ?: return
        if (_state.value.busy || _state.value.loading) return
        _state.value = _state.value.copy(busy = true)
        scope.launch {
            val result = when (pending) {
                is MichiLinkTrustPending.Source -> admin.revokeSource(pending.expected)
                is MichiLinkTrustPending.Home -> admin.revokeHome(pending.expected)
            }
            val notice = MichiLinkTrustNotice(
                outcome = result.outcome,
                subject = when (pending) {
                    is MichiLinkTrustPending.Source -> MichiLinkTrustSubject.SOURCE
                    is MichiLinkTrustPending.Home -> MichiLinkTrustSubject.HOME
                },
            )
            val inventory = result.inventory
            _state.value = if (inventory == null) {
                // The mutation may have landed, but the inventory is unreadable,
                // so no row may be drawn from the old snapshot.
                _state.value.copy(
                    busy = false,
                    pending = null,
                    notice = notice,
                    homes = emptyList(),
                    sources = emptyList(),
                    loadFailure = MichiLinkTrustLoadFailure.KEYSTORE_UNAVAILABLE,
                )
            } else {
                _state.value.copy(
                    busy = false,
                    pending = null,
                    notice = notice,
                    homes = inventory.homes,
                    sources = inventory.sources,
                    loadFailure = null,
                    loading = false,
                )
            }
        }
    }
}
