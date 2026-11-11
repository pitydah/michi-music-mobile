package org.michimusic.mobile.ui.screens

import org.michimusic.player.AudioRouteStatus

/** Observed route, requested route and last-known route are separate facts. */
fun settingsAudioRouteSummary(status: AudioRouteStatus): String = when (status) {
    AudioRouteStatus.NoDevice -> "No se ha observado una salida activa."
    is AudioRouteStatus.Detected -> "Dispositivo detectado: ${status.device.name}. Ruta sin confirmar."
    is AudioRouteStatus.Requested -> "Salida solicitada: ${status.device.name}. Esperando confirmación."
    is AudioRouteStatus.Confirmed -> "Salida observada en Android: ${status.device.name}."
    is AudioRouteStatus.RoutedElsewhere ->
        "Android usa ${status.actual.name}; se había solicitado ${status.requested.name}."
    is AudioRouteStatus.LastObserved -> "Última ruta conocida: ${status.device.name}. No es observación actual."
    is AudioRouteStatus.Failed -> "Android no pudo aplicar la ruta solicitada (${status.reason.name})."
    is AudioRouteStatus.Unknown -> "Estado de salida no confirmado: ${status.reason}."
}
