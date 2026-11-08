package org.michimusic.mobile.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.ErrorColor
import org.michimusic.player.AudioRouteStatus

/** A measured AudioTrack route is distinct from an available or requested device. */
enum class AeroRouteCertainty { OBSERVED, REQUESTED, HISTORICAL, DETECTED, UNKNOWN, ERROR }

fun aeroRouteCertainty(status: AudioRouteStatus): AeroRouteCertainty = when (status) {
    is AudioRouteStatus.Confirmed, is AudioRouteStatus.RoutedElsewhere -> AeroRouteCertainty.OBSERVED
    is AudioRouteStatus.Requested -> AeroRouteCertainty.REQUESTED
    is AudioRouteStatus.LastObserved -> AeroRouteCertainty.HISTORICAL
    is AudioRouteStatus.Detected -> AeroRouteCertainty.DETECTED
    is AudioRouteStatus.Failed -> AeroRouteCertainty.ERROR
    is AudioRouteStatus.Unknown, AudioRouteStatus.NoDevice -> AeroRouteCertainty.UNKNOWN
}

fun aeroRouteCertaintyLabel(level: AeroRouteCertainty): String = when (level) {
    AeroRouteCertainty.OBSERVED -> "Ruta AudioTrack observada"
    AeroRouteCertainty.REQUESTED -> "Salida solicitada · sin confirmar"
    AeroRouteCertainty.HISTORICAL -> "Última ruta observada · histórica"
    AeroRouteCertainty.DETECTED -> "Dispositivo detectado · no verificado"
    AeroRouteCertainty.UNKNOWN -> "Ruta actual desconocida"
    AeroRouteCertainty.ERROR -> "Error de enrutamiento"
}

fun aeroRouteMotionDurationMs(reducedMotion: Boolean, highContrast: Boolean): Int =
    if (reducedMotion || highContrast) 0 else MichiMotion.durationStandard

/** Finite icon replacement keyed by route evidence; never keyed to transport position. */
@Composable
fun AeroObservedRouteIcon(
    observationKey: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = TertiaryCyan,
) {
    val preferences = LocalVisualPreferences.current
    val duration = aeroRouteMotionDurationMs(preferences.reducedMotion, preferences.highContrast)
    AnimatedContent(
        targetState = observationKey to icon,
        modifier = modifier,
        transitionSpec = {
            if (duration == 0) EnterTransition.None togetherWith ExitTransition.None
            else fadeIn(tween(duration)) togetherWith fadeOut(tween(MichiMotion.durationFast))
        },
        contentAlignment = Alignment.Center,
        label = "aero_route_observed_icon",
    ) { (_, observedIcon) ->
        Icon(imageVector = observedIcon, contentDescription = null, tint = tint,
            modifier = Modifier.size(28.dp))
    }
}

/** Read-only evidence indicator. It does NOT certify the physical DAC format. */
@Composable
fun AeroRouteCertaintyLabel(
    status: AudioRouteStatus,
    modifier: Modifier = Modifier,
    style: TextStyle,
) {
    val level = aeroRouteCertainty(status)
    val target = when (level) {
        AeroRouteCertainty.OBSERVED -> TertiaryCyan
        AeroRouteCertainty.ERROR -> ErrorColor
        else -> TextSecondary
    }
    Row(modifier = modifier.testTag("aero_route_certainty"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.size(6.dp).background(target, CircleShape))
        AeroObservedText(
            value = aeroRouteCertaintyLabel(level),
            style = style,
            color = target,
            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
            maxLines = 2,
        )
    }
}
