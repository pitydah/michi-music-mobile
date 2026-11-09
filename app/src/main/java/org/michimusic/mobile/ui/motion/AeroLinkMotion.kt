package org.michimusic.mobile.ui.motion

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.mobile.ui.theme.ErrorColor
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.TertiaryCyan

/** These labels describe the exact state emitted by SyncViewModel. Discovery is not pairing. */
fun aeroLinkHeadline(state: SyncConnectionState, discovering: Boolean = false): String = when {
    discovering || state == SyncConnectionState.DISCOVERING -> "Buscando dispositivos Michi..."
    state == SyncConnectionState.CONNECTED -> "Conexión establecida"
    state == SyncConnectionState.CONNECTING -> "Conectando al dispositivo"
    state == SyncConnectionState.PAIRING || state == SyncConnectionState.PAIRING_REQUIRED -> "Vinculación pendiente"
    state == SyncConnectionState.PAIRED -> "Identidad vinculada"
    state == SyncConnectionState.REVOKED || state == SyncConnectionState.UNAUTHORIZED -> "Acceso no autorizado"
    state == SyncConnectionState.ERROR || state == SyncConnectionState.AUTH_ERROR -> "Revisa la conexión"
    state == SyncConnectionState.OFFLINE -> "Dispositivo sin conexión"
    else -> "Listo para buscar dispositivos"
}

/** A neutral/pending status never becomes an "online" success just because a peer was discovered. */
enum class AeroLinkEvidenceLevel { NEUTRAL, PENDING, VERIFIED, WARNING }

fun aeroLinkEvidenceLevel(state: SyncConnectionState): AeroLinkEvidenceLevel = when (state) {
    SyncConnectionState.CONNECTED -> AeroLinkEvidenceLevel.VERIFIED
    SyncConnectionState.CONNECTING, SyncConnectionState.DISCOVERING,
    SyncConnectionState.PAIRING, SyncConnectionState.PAIRING_REQUIRED -> AeroLinkEvidenceLevel.PENDING
    SyncConnectionState.REVOKED, SyncConnectionState.UNAUTHORIZED,
    SyncConnectionState.AUTH_ERROR, SyncConnectionState.ERROR -> AeroLinkEvidenceLevel.WARNING
    else -> AeroLinkEvidenceLevel.NEUTRAL
}

/** The deviceType comes from the discovered peer; this label does not imply a session. */
fun aeroStreamPeerEvidenceLabel(advertisedStreamType: Boolean): String? =
    if (advertisedStreamType) "Michi Music Stream · dispositivo anunciado" else null

@Composable
fun aeroLinkEvidenceColor(level: AeroLinkEvidenceLevel): Color {
    val prefs = LocalVisualPreferences.current
    val target = when (level) {
        AeroLinkEvidenceLevel.VERIFIED -> TertiaryCyan
        AeroLinkEvidenceLevel.WARNING -> ErrorColor
        AeroLinkEvidenceLevel.PENDING -> Color(0xFFE5A93C)
        AeroLinkEvidenceLevel.NEUTRAL -> OnSurfaceVariant
    }
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = if (prefs.reducedMotion || prefs.highContrast) snap() else
            tween(MichiMotion.durationStandard, easing = MichiMotion.easingDecelerate),
        label = "aero_link_evidence_color",
    )
    return color
}
