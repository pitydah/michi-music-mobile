package org.michimusic.mobile.ui.components

import org.michimusic.player.AudioRouteStatus

/**
 * Truthful UI summary: merely enumerating a USB/Bluetooth device NEVER proves that
 * the current AudioTrack is routed through it. An observed route does not certify
 * hardware sample-rate, DSP bypass, or bit-perfect audio.
 */
data class AudioRouteEvidence(
    val headline: String,
    val deviceLabel: String,
    val explanation: String,
    val hasCurrentEvidence: Boolean,
)

fun audioRouteEvidence(status: AudioRouteStatus): AudioRouteEvidence = when (status) {
    is AudioRouteStatus.Confirmed -> AudioRouteEvidence(
        "Ruta confirmada por AudioTrack", status.device.name.ifBlank { "Dispositivo de salida" },
        "Android confirmó esta ruta para la pista de audio. No certifica el formato físico del DAC ni bit-perfect.", true,
    )
    is AudioRouteStatus.RoutedElsewhere -> AudioRouteEvidence(
        "Se está utilizando otra salida", status.actual.name.ifBlank { "Otra salida" },
        "Solicitada: ${status.requested.name.ifBlank { "Salida desconocida" }}. La ruta real no coincide con la solicitada.", true,
    )
    is AudioRouteStatus.Requested -> AudioRouteEvidence(
        "Ruta solicitada, pendiente", status.device.name.ifBlank { "Dispositivo solicitado" },
        "Todavía no hay confirmación de que AudioTrack esté enviando audio a esta salida.", false,
    )
    is AudioRouteStatus.Detected -> AudioRouteEvidence(
        "Dispositivo detectado", status.device.name.ifBlank { "Salida disponible" },
        "Estar conectado no demuestra que esta sea la ruta efectiva de reproducción.", false,
    )
    is AudioRouteStatus.LastObserved -> AudioRouteEvidence(
        "Última ruta observada", status.device.name.ifBlank { "Salida anterior" },
        "Información histórica: no existe una pista AudioTrack activa que confirme esta ruta ahora.", false,
    )
    is AudioRouteStatus.Failed -> AudioRouteEvidence(
        "No se pudo confirmar la ruta", "Error de enrutamiento",
        "Motivo: ${status.reason.name}. Comprueba la conexión y vuelve a iniciar la reproducción.", false,
    )
    is AudioRouteStatus.Unknown -> AudioRouteEvidence(
        "Ruta no verificable", "Salida desconocida", status.reason.ifBlank { "Android no proporcionó evidencia suficiente." }, false,
    )
    AudioRouteStatus.NoDevice -> AudioRouteEvidence(
        "Sin ruta confirmada", "Sin salida identificada",
        "Puedes consultar los dispositivos disponibles, pero no deben confundirse con la ruta de audio activa.", false,
    )
}
