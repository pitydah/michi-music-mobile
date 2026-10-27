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

/** Absence of a total is not 0%. */
fun syncProgressCaption(completed: Int, total: Int): String =
    if (total <= 0) "${completed.coerceAtLeast(0)} pistas procesadas · total desconocido"
    else "${completed.coerceIn(0,total)} de $total pistas"

fun syncProgressFraction(completed: Int, total: Int): Float? =
    if (total <= 0) null else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
