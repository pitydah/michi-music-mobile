package org.michimusic.mobile.playback

import org.michimusic.core.models.AudioProcessingMode

/** Configured does not mean audio samples were observed with this mode. */
internal enum class AudioModeQuickStatus { CONFIGURED, UNAVAILABLE, WRONG_DESTINATION, OUTPUT_CHANGED, REJECTED }
internal data class AudioModeQuickResult(val status: AudioModeQuickStatus, val message: String)

/** Keep AudioModeManager as the one persistent source of truth. */
internal interface AudioModeQuickEngine {
    fun currentMode(): AudioProcessingMode?
    fun configure(mode: AudioProcessingMode): Boolean
}

internal class SignalTruthAudioModeCoordinator(
    private val engine: AudioModeQuickEngine,
    private val authorityLease: (() -> SignalTruthLocalAuthorityLease?)? = null,
    private val hasLocalAuthority: () -> Boolean,
) {
    private fun current(lease: SignalTruthLocalAuthorityLease?): Boolean =
        hasLocalAuthority() && (authorityLease == null ||
            SignalTruthLocalAuthorityPolicy.unchanged(lease, runCatching { authorityLease?.invoke() }.getOrNull()))

    fun choose(destination: SignalDestination, mode: AudioProcessingMode): AudioModeQuickResult {
        val lease = authorityLease?.let { runCatching { it() }.getOrNull() }
        if (destination != SignalDestination.LOCAL || !current(lease)) {
            return AudioModeQuickResult(AudioModeQuickStatus.WRONG_DESTINATION,
                "El modo DSP del telefono no controla este destino.")
        }
        val previous = runCatching { engine.currentMode() }.getOrNull()
            ?: return AudioModeQuickResult(AudioModeQuickStatus.UNAVAILABLE,
                "AudioModeManager no esta disponible; no se cambio el modo.")
        if (!current(lease)) return AudioModeQuickResult(AudioModeQuickStatus.OUTPUT_CHANGED,
            "La autoridad de salida cambió antes de configurar DSP.")
        if (previous != mode) {
            val changed = runCatching { engine.configure(mode) }.getOrDefault(false)
            if (!changed || runCatching { engine.currentMode() }.getOrNull() != mode) {
                return AudioModeQuickResult(AudioModeQuickStatus.REJECTED,
                    "El cambio no fue confirmado por AudioModeManager.")
            }
        }
        if (!current(lease)) return AudioModeQuickResult(AudioModeQuickStatus.OUTPUT_CHANGED,
            "La salida cambió durante el comando; no se confirma DSP en el nuevo destino.")
        return AudioModeQuickResult(AudioModeQuickStatus.CONFIGURED,
            "Modo configurado: ${mode.name}. No acredita audio procesado ni bit-perfect.")
    }
}
