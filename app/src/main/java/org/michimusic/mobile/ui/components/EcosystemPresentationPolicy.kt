package org.michimusic.mobile.ui.components

/** UI-only policy. Does not control the transfer state machine or authorize a transfer. */
data class AirCatUiPresentation(
    val label: String,
    val showPause: Boolean = false,
    val showResume: Boolean = false,
    val showCancel: Boolean = false,
    val isError: Boolean = false,
    val isTerminal: Boolean = false,
)

/** Unknown values fail closed: no actionable transport controls. */
fun airCatUiPresentation(stateName: String): AirCatUiPresentation = when (stateName.uppercase()) {
    "PENDING_AUTHORIZATION" -> AirCatUiPresentation("Esperando autorización", showCancel = true)
    "NEGOTIATING" -> AirCatUiPresentation("Negociando transferencia", showCancel = true)
    "TRANSFERRING" -> AirCatUiPresentation("Transfiriendo música", showPause = true, showCancel = true)
    "VERIFYING" -> AirCatUiPresentation("Verificando integridad", showCancel = true)
    "PAUSED" -> AirCatUiPresentation("Transferencia pausada", showResume = true, showCancel = true)
    "COMPLETED" -> AirCatUiPresentation("Transferencia completada", isTerminal = true)
    "FAILED" -> AirCatUiPresentation("Transferencia fallida", isTerminal = true, isError = true)
    "CANCELLED" -> AirCatUiPresentation("Transferencia cancelada", isTerminal = true)
    else -> AirCatUiPresentation("Estado no reconocido")
}

/**
 * Normalized progress counters. The wording — plural agreement included — belongs to
 * the string resources, so this stays a pure, testable decision and a single-track
 * transfer can read "1 de 1 pista" instead of a hard-coded plural.
 */
data class SyncProgressCounters(
    val completed: Int,
    val total: Int,
) {
    /** Absence of a total is unknown, not zero. */
    val hasKnownTotal: Boolean get() = total > 0
}

fun syncProgressCounters(completed: Int, total: Int): SyncProgressCounters =
    if (total <= 0) SyncProgressCounters(completed.coerceAtLeast(0), 0)
    else SyncProgressCounters(completed.coerceIn(0, total), total)

fun syncProgressFraction(completed: Int, total: Int): Float? =
    if (total <= 0) null else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
