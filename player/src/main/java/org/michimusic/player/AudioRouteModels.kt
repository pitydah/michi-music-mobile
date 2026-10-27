package org.michimusic.player

/**
 * Representa el estado verificable de enrutamiento de audio hacia un dispositivo de salida.
 * Sustituye la asunción optimista de que un dongle conectado es inmediatamente la salida efectiva.
 */
sealed interface AudioRouteStatus {
    data object NoDevice : AudioRouteStatus
    data class Detected(val device: OutputDescriptor) : AudioRouteStatus
    data class Requested(val device: OutputDescriptor) : AudioRouteStatus
    /** A matching request, or an observed default route when no device was requested. */
    data class Confirmed(val device: OutputDescriptor, val observedAtMs: Long) : AudioRouteStatus
    /** Android is using a different output from the requested device. */
    data class RoutedElsewhere(val requested: OutputDescriptor, val actual: OutputDescriptor, val observedAtMs: Long) : AudioRouteStatus
    /** Historical observation; no currently attached AudioTrack proves the route. */
    data class LastObserved(val device: OutputDescriptor, val observedAtMs: Long) : AudioRouteStatus
    data class Failed(val deviceId: Int?, val reason: RouteFailure) : AudioRouteStatus
    data class Unknown(val reason: String) : AudioRouteStatus
}

/**
 * Descriptores de capacidad y atributos declarados por el sistema/dispositivo.
 * No asume frecuencias efectivas de reproducción ni bit-perfect sin prueba física.
 */
data class OutputDescriptor(
    val androidDeviceId: Int,
    val name: String,
    val androidType: Int,
    val announcedSampleRates: List<Int> = emptyList(),
    val announcedChannelCounts: List<Int> = emptyList(),
    val announcedEncodings: List<Int> = emptyList(),
)

enum class RouteFailure {
    DEVICE_NOT_FOUND,
    ROUTING_REJECTED,
    DISCONNECTED,
    AUDIO_TRACK_ERROR,
    UNSUPPORTED_CONFIGURATION,
}

/**
 * Abstracción de consulta y eventos de dispositivos de audio para permitir
 * inyección de dependencias y pruebas unitarias deterministas.
 */
interface AudioDeviceSource {
    fun getOutputDevices(): List<OutputDescriptor>
    fun addListener(listener: DeviceChangeListener)
    fun removeListener(listener: DeviceChangeListener)

    interface DeviceChangeListener {
        fun onDevicesChanged()
    }
}
