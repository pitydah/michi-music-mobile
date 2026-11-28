package org.michimusic.mobile.devices.aircat.experience

import org.michimusic.link.home.HomeEnrollmentPhase
import org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeAuthority
import org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeSource

/**
 * A71-09: state and copy for Home trust administration.
 *
 * The screen shows exact inventory records, never a derived "ready" flag. Every
 * destructive action keeps the snapshot it was requested with, so the store can
 * reject a stale confirmation instead of revoking a peer the user never saw.
 */

/** The only local read that can fail before any inventory exists. */
internal enum class MichiLinkTrustLoadFailure { KEYSTORE_UNAVAILABLE }

/**
 * What actually happened to a destructive local revocation.
 *
 * `REVOKED` is only reported after the store's durable commit succeeded.
 * `HOME_CHANGED` means the compare-and-revoke rejected the snapshot. `ALREADY_REMOVED`
 * means the record was gone before the tap. `PERSISTENCE_ERROR` means the
 * mutation or its verification could not be confirmed, so the screen must not
 * claim the authorisation was withdrawn.
 */
internal enum class MichiLinkTrustOutcome {
    REVOKED,
    HOME_CHANGED,
    ALREADY_REMOVED,
    PERSISTENCE_ERROR,
}

/** Whether the revoked subject was one source binding or a whole Home root. */
internal enum class MichiLinkTrustSubject { SOURCE, HOME }

/** A destructive action waiting for its second, explicit confirmation. */
internal sealed interface MichiLinkTrustPending {
    /** The exact record the store must still match when the user confirms. */
    val impact: String

    data class Source(
        val expected: AirCatEnrolledHomeSource,
        override val impact: String = airCatSourceRevocationImpact(expected),
    ) : MichiLinkTrustPending

    data class Home(
        val expected: AirCatEnrolledHomeAuthority,
        override val impact: String = airCatHomeRevocationImpact(expected),
    ) : MichiLinkTrustPending
}

/** The one sentence shown after a confirmation, chosen by the real outcome. */
internal data class MichiLinkTrustNotice(
    val outcome: MichiLinkTrustOutcome,
    val subject: MichiLinkTrustSubject,
) {
    val message: String
        get() = when (outcome) {
            MichiLinkTrustOutcome.REVOKED -> when (subject) {
                MichiLinkTrustSubject.SOURCE ->
                    "Origen desvinculado: la confianza Home local se retiró y el cambio quedó guardado."
                MichiLinkTrustSubject.HOME ->
                    "Autoridad Home desvinculada: se retiró la confianza local de sus orígenes y el cambio quedó guardado."
            }
            MichiLinkTrustOutcome.HOME_CHANGED ->
                "La confianza Home cambió desde que abriste esta pantalla. No se modificó nada; revisá la lista actualizada."
            MichiLinkTrustOutcome.ALREADY_REMOVED ->
                "Ese vínculo ya no existía en este dispositivo. No había nada que revocar."
            MichiLinkTrustOutcome.PERSISTENCE_ERROR ->
                "No pudimos confirmar que la revocación se guardara. Revisá la lista y, si el vínculo sigue ahí, volvé a intentarlo."
        }
}

/** MIM-05.5: what the QR-anchored enrollment has actually observed. */
internal data class HomeEnrollmentUiState(
    val sourceMichiId: String? = null,
    val phase: HomeEnrollmentPhase = HomeEnrollmentPhase.NOT_ENROLLED,
    val requestId: String? = null,
    val message: String? = null,
    val busy: Boolean = false,
) {
    val isPending: Boolean get() = phase == HomeEnrollmentPhase.ENROLLMENT_PENDING
    val isEnrolled: Boolean get() = phase == HomeEnrollmentPhase.ENROLLED_OFFLINE
    val isAuthenticated: Boolean get() = phase == HomeEnrollmentPhase.AUTHENTICATED_ONLINE
}

/** Everything the trust screen may draw. No client, no grant, no mutation. */
internal data class MichiLinkTrustState(
    val loading: Boolean = true,
    val loadFailure: MichiLinkTrustLoadFailure? = null,
    val homes: List<AirCatEnrolledHomeAuthority> = emptyList(),
    val sources: List<AirCatEnrolledHomeSource> = emptyList(),
    val pending: MichiLinkTrustPending? = null,
    val busy: Boolean = false,
    val notice: MichiLinkTrustNotice? = null,
    val enrollment: HomeEnrollmentUiState = HomeEnrollmentUiState(),
    val enrollmentAvailable: Boolean = false,
) {
    val hasInventory: Boolean get() = homes.isNotEmpty() || sources.isNotEmpty()

    /** Actions are only offered while the inventory is readable and settled. */
    val canRequest: Boolean get() = !loading && !busy && loadFailure == null

    /** MIM-05.5: enrollment needs an anchor and a coordinator wired by Koin. */
    val canEnroll: Boolean get() = canRequest && enrollmentAvailable
}

/** One intent per user decision; the controller owns every state transition. */
internal sealed interface MichiLinkTrustIntent {
    data object Refresh : MichiLinkTrustIntent
    data class RequestSourceRevocation(val source: AirCatEnrolledHomeSource) : MichiLinkTrustIntent
    data class RequestHomeRevocation(val home: AirCatEnrolledHomeAuthority) : MichiLinkTrustIntent
    data object ConfirmRevocation : MichiLinkTrustIntent
    data object CancelRevocation : MichiLinkTrustIntent
    data object DismissNotice : MichiLinkTrustIntent

    // MIM-05.5: explicit, user-driven enrollment; no discovery or automatic approval.
    data class RequestEnrollment(val source: AirCatEnrolledHomeSource) : MichiLinkTrustIntent
    data object RefreshEnrollment : MichiLinkTrustIntent
    data object PollEnrollment : MichiLinkTrustIntent
    data object AuthenticateEnrollment : MichiLinkTrustIntent
    data object CancelEnrollment : MichiLinkTrustIntent
}

/** Short, stable rendering for base64url identifiers and digests. */
internal fun airCatShortId(value: String): String =
    if (value.length <= 18) value else value.take(12) + "…" + value.takeLast(6)

internal fun airCatShortDigest(value: String): String =
    if (value.length <= 20) value else value.take(20) + "…"

/** Impact copy for removing one source binding, fixed when the user asks. */
internal fun airCatSourceRevocationImpact(expected: AirCatEnrolledHomeSource): String =
    "Se retirará la confianza Home local del origen ${airCatShortId(expected.sourceMichiId)}. " +
        "Su endpoint fijado ${expected.pinnedBaseUrl} dejará de aceptarse. " +
        "Las transferencias en curso perderán autorización en su próxima comprobación de continuidad. " +
        "La música ya recibida no se borra; los demás orígenes y la autoridad Home se conservan."

/** Impact copy for removing a whole Home root, with its exact source count. */
internal fun airCatHomeRevocationImpact(expected: AirCatEnrolledHomeAuthority): String {
    val noun = if (expected.sourceCount == 1) "origen registrado" else "orígenes registrados"
    return "Se retirará la autoridad Home ${airCatShortId(expected.homeId)} y la confianza local " +
        "de sus ${expected.sourceCount} $noun. Las transferencias perderán autorización en su " +
        "próxima comprobación. La música ya recibida no se borra. El QR anterior de esta autoridad " +
        "quedará bloqueado incluso tras reiniciar; no se afectan otras autoridades Home."
}
