package org.michimusic.core.models

import kotlinx.serialization.Serializable

/**
 * Modo de procesamiento de audio en la cadena de reproducción.
 */
@Serializable
enum class AudioProcessingMode {
    /** Ajuste automático según la salida (altavoz, auriculares, DAC USB). */
    AUTO,
    /** Bypass total de ecualizador, efectos de audio y ReplayGain para máxima fidelidad técnica. */
    TRANSPARENT,
    /** Ecualizador, efectos acústicos y ReplayGain habilitados según configuración del usuario. */
    DSP,
}

/**
 * Estado verificado de bit-perfect en la salida de audio.
 */
@Serializable
enum class BitPerfectStatus {
    NOT_REQUESTED,
    REQUESTED,
    VERIFIED,
    UNVERIFIABLE,
}

/**
 * Descriptores de formato de señal de audio.
 */
@Serializable
data class SignalFormat(
    val codec: String,
    val bitDepth: Int,
    val sampleRate: Int,
    val channels: Int,
)

/**
 * Estado de la cadena de efectos de software/hardware DSP.
 */
@Serializable
data class DspPipelineStatus(
    val isEqActive: Boolean = false,
    val isReplayGainActive: Boolean = false,
    val replayGainAppliedDb: Float = 0f,
    val bassBoostActive: Boolean = false,
    val virtualizerActive: Boolean = false,
)

/**
 * Observación integral y veraz de la señal de audio en sus diferentes etapas.
 * Los campos no verificables o no medidos permanecen como null o UNVERIFIABLE.
 */
@Serializable
data class AudioSignalObservation(
    val sourceFormat: SignalFormat? = null,
    val decodedFormat: SignalFormat? = null,
    val dspStatus: DspPipelineStatus = DspPipelineStatus(),
    val routeStatus: String = "DESCONOCIDO",
    val outputConfig: SignalFormat? = null,
    val bitPerfect: BitPerfectStatus = BitPerfectStatus.UNVERIFIABLE,
    val observedAtMs: Long = 0L,
)
