package org.michimusic.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.michimusic.core.models.AudioProcessingMode

private const val TAG = "AudioModeManager"
private const val PREFS_NAME = "michi_audio_mode_prefs"
private const val KEY_AUDIO_MODE = "audio_processing_mode"

/**
 * Autoridad única persistente para el modo de procesamiento de audio
 * (AUTO, TRANSPARENT, DSP).
 */
class AudioModeManager(
    context: Context,
    private val audioEffects: MichiAudioEffects? = null,
    private val replayGainProcessor: ReplayGainAudioProcessor? = null,
    private val usbDacManager: UsbDacManager? = null,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentMode = MutableStateFlow(loadInitialMode())
    val currentMode: StateFlow<AudioProcessingMode> = _currentMode.asStateFlow()

    init {
        applyMode(_currentMode.value)
    }

    private fun loadInitialMode(): AudioProcessingMode {
        val saved = prefs.getString(KEY_AUDIO_MODE, AudioProcessingMode.DSP.name)
        return runCatching { AudioProcessingMode.valueOf(saved ?: AudioProcessingMode.DSP.name) }
            .getOrDefault(AudioProcessingMode.DSP)
    }

    fun setMode(mode: AudioProcessingMode) {
        prefs.edit().putString(KEY_AUDIO_MODE, mode.name).apply()
        _currentMode.value = mode
        applyMode(mode)
    }

    fun applyMode(mode: AudioProcessingMode) {
        runCatching { Log.i(TAG, "Aplicando modo de audio: $mode") }
        when (mode) {
            AudioProcessingMode.TRANSPARENT -> {
                audioEffects?.isBypassedByMode = true
                replayGainProcessor?.isBypassed = true
            }
            AudioProcessingMode.DSP -> {
                audioEffects?.isBypassedByMode = false
                replayGainProcessor?.isBypassed = false
            }
            AudioProcessingMode.AUTO -> {
                // En AUTO: si hay un DAC USB conectado, aplicar transparencia conservadora; si es altavoz/BT, mantener DSP
                val isUsbConnected = usbDacManager?.dacState?.value?.isConnected == true
                audioEffects?.isBypassedByMode = isUsbConnected
                replayGainProcessor?.isBypassed = isUsbConnected
            }
        }
    }
}
