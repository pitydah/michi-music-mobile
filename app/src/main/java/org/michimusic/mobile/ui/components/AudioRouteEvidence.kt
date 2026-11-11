package org.michimusic.mobile.ui.components

import android.media.AudioDeviceInfo
import org.michimusic.player.AudioRouteStatus
import org.michimusic.player.OutputDescriptor

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
        "Ruta confirmada por AudioTrack", humanOutputLabel(status.device),
        "Android confirmó esta ruta para la pista de audio. No certifica el formato físico del DAC ni bit-perfect.", true,
    )
    is AudioRouteStatus.RoutedElsewhere -> AudioRouteEvidence(
        "Se está utilizando otra salida", humanOutputLabel(status.actual),
        "Solicitada: ${humanOutputLabel(status.requested)}. La ruta real no coincide con la solicitada.", true,
    )
    is AudioRouteStatus.Requested -> AudioRouteEvidence(
        "Ruta solicitada, pendiente", humanOutputLabel(status.device),
        "Todavía no hay confirmación de que AudioTrack esté enviando audio a esta salida.", false,
    )
    is AudioRouteStatus.Detected -> AudioRouteEvidence(
        "Dispositivo detectado", humanOutputLabel(status.device),
        "Estar conectado no demuestra que esta sea la ruta efectiva de reproducción.", false,
    )
    is AudioRouteStatus.LastObserved -> AudioRouteEvidence(
        "Última ruta observada", humanOutputLabel(status.device),
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

/**
 * Human-safe label derived from the observed Android type. Build/model codes
 * (for example `sdk_gphone64_x86_64`) are never promoted to a user-facing route
 * name; they remain available only in technical details.
 */
internal fun humanOutputLabel(device: OutputDescriptor): String {
    val typeLabel = when (device.androidType) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Altavoz del teléfono"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Auricular del teléfono"
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Auriculares con cable"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Auriculares Bluetooth"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "DAC USB"
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
        AudioDeviceInfo.TYPE_DOCK -> "Base de conexión"
        else -> "Salida Android sin identificar"
    }
    val namedTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )
    val name = device.name.trim()
    val humanName = device.androidType in namedTypes &&
        name.isNotEmpty() &&
        name.length <= 48 &&
        !name.contains('_') &&
        !name.startsWith("sdk_", ignoreCase = true) &&
        name.any(Char::isLetter)
    return if (humanName) name else typeLabel
}

/** Observation identity is authoritative; selection and enumeration are not. */
fun isObservedOutputInUse(status: AudioRouteStatus, deviceId: Int): Boolean = when (status) {
    is AudioRouteStatus.Confirmed -> status.device.androidDeviceId == deviceId
    is AudioRouteStatus.RoutedElsewhere -> status.actual.androidDeviceId == deviceId
    else -> false
}

/** Never apply the global route's CONFIRMED label to another connected dongle. */
fun usbDacRouteLabel(status: AudioRouteStatus, deviceId: Int): String = when {
    isObservedOutputInUse(status, deviceId) -> "EN USO"
    status is AudioRouteStatus.Requested && status.device.androidDeviceId == deviceId -> "SOLICITADO"
    status is AudioRouteStatus.Detected && status.device.androidDeviceId == deviceId -> "DETECTADO"
    status is AudioRouteStatus.RoutedElsewhere && status.requested.androidDeviceId == deviceId -> "RUTA DIFERENTE"
    status is AudioRouteStatus.LastObserved && status.device.androidDeviceId == deviceId -> "ÚLTIMA OBSERVACIÓN"
    status is AudioRouteStatus.Failed && status.deviceId == deviceId -> "ERROR DE RUTA"
    else -> "DISPONIBLE"
}
