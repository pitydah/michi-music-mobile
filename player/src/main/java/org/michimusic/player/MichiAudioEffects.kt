package org.michimusic.player

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "MichiAudioEffects"
private const val EQ_PREFS = "michi_equalizer_prefs"
private const val KEY_EQ_ENABLED = "eq_enabled"
private const val KEY_BASS_STRENGTH = "bass_strength"
private const val KEY_VIRTUALIZER_STRENGTH = "virtualizer_strength"
private const val KEY_PRESET_NAME = "selected_preset"

data class EqualizerBandState(
    val index: Int,
    val centerFreqHz: Int,
    val minGainMilliDb: Int,
    val maxGainMilliDb: Int,
    var currentGainMilliDb: Int,
)

data class AudioEffectsState(
    val isEnabled: Boolean = true,
    val selectedPreset: String = "Synthwave",
    val bassBoostStrength: Int = 350, // 0 to 1000
    val virtualizerStrength: Int = 200, // 0 to 1000
    val bands: List<EqualizerBandState> = emptyList(),
)

class MichiAudioEffects(
    private val context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(EQ_PREFS, Context.MODE_PRIVATE)

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var currentSessionId: Int = 0

    private val _effectsState = MutableStateFlow(AudioEffectsState())
    val effectsState: StateFlow<AudioEffectsState> = _effectsState.asStateFlow()

    init {
        initFallbackBands()
    }

    fun bindAudioSession(sessionId: Int) {
        if (sessionId == 0 || sessionId == currentSessionId) return
        currentSessionId = sessionId
        releaseEffects()

        try {
            equalizer = Equalizer(0, sessionId).apply {
                enabled = prefs.getBoolean(KEY_EQ_ENABLED, true)
            }
            bassBoost = BassBoost(0, sessionId).apply {
                enabled = prefs.getBoolean(KEY_EQ_ENABLED, true)
                val str = prefs.getInt(KEY_BASS_STRENGTH, 350).toShort()
                if (strengthSupported) setStrength(str)
            }
            virtualizer = Virtualizer(0, sessionId).apply {
                enabled = prefs.getBoolean(KEY_EQ_ENABLED, true)
                val str = prefs.getInt(KEY_VIRTUALIZER_STRENGTH, 200).toShort()
                if (strengthSupported) setStrength(str)
            }

            syncBandsFromHardware()
            applySavedBandGains()
            Log.i(TAG, "AudioEffects vinculados con sesión $sessionId")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron inicializar AudioEffects en hardware, usando emulación de bandas", e)
            initFallbackBands()
        }
    }

    private fun syncBandsFromHardware() {
        val eq = equalizer ?: run {
            initFallbackBands()
            return
        }

        try {
            val numBands = eq.numberOfBands.toInt()
            val minRange = eq.bandLevelRange[0].toInt()
            val maxRange = eq.bandLevelRange[1].toInt()

            val bandList = (0 until numBands).map { idx ->
                val centerFreq = eq.getCenterFreq(idx.toShort()) / 1000 // Convert milliHz to Hz
                val savedGain = prefs.getInt("band_$idx", eq.getBandLevel(idx.toShort()).toInt())
                EqualizerBandState(
                    index = idx,
                    centerFreqHz = centerFreq,
                    minGainMilliDb = minRange,
                    maxGainMilliDb = maxRange,
                    currentGainMilliDb = savedGain,
                )
            }

            _effectsState.value = _effectsState.value.copy(
                isEnabled = eq.enabled,
                bands = bandList,
                bassBoostStrength = prefs.getInt(KEY_BASS_STRENGTH, 350),
                virtualizerStrength = prefs.getInt(KEY_VIRTUALIZER_STRENGTH, 200),
                selectedPreset = prefs.getString(KEY_PRESET_NAME, "Synthwave") ?: "Synthwave",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error leyendo bandas del Equalizer", e)
            initFallbackBands()
        }
    }

    private fun initFallbackBands() {
        val defaultFrequencies = listOf(60, 230, 910, 3600, 14000)
        val bandList = defaultFrequencies.mapIndexed { idx, freq ->
            val savedGain = prefs.getInt("band_$idx", 0)
            EqualizerBandState(
                index = idx,
                centerFreqHz = freq,
                minGainMilliDb = -1200,
                maxGainMilliDb = 1200,
                currentGainMilliDb = savedGain,
            )
        }
        _effectsState.value = _effectsState.value.copy(
            isEnabled = prefs.getBoolean(KEY_EQ_ENABLED, true),
            bands = bandList,
            bassBoostStrength = prefs.getInt(KEY_BASS_STRENGTH, 350),
            virtualizerStrength = prefs.getInt(KEY_VIRTUALIZER_STRENGTH, 200),
            selectedPreset = prefs.getString(KEY_PRESET_NAME, "Synthwave") ?: "Synthwave",
        )
    }

    private fun applySavedBandGains() {
        val eq = equalizer ?: return
        _effectsState.value.bands.forEach { band ->
            try {
                eq.setBandLevel(band.index.toShort(), band.currentGainMilliDb.toShort())
            } catch (e: Exception) {
                Log.w(TAG, "Error aplicando ganancia a banda ${band.index}", e)
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EQ_ENABLED, enabled).apply()
        try {
            equalizer?.enabled = enabled
            bassBoost?.enabled = enabled
            virtualizer?.enabled = enabled
        } catch (e: Exception) {
            Log.w(TAG, "Error cambiando estado de efectos", e)
        }
        _effectsState.value = _effectsState.value.copy(isEnabled = enabled)
    }

    fun setBandGain(bandIndex: Int, milliDb: Int) {
        val currentBands = _effectsState.value.bands.toMutableList()
        val targetIdx = currentBands.indexOfFirst { it.index == bandIndex }
        if (targetIdx != -1) {
            val updatedBand = currentBands[targetIdx].copy(currentGainMilliDb = milliDb)
            currentBands[targetIdx] = updatedBand

            try {
                equalizer?.setBandLevel(bandIndex.toShort(), milliDb.toShort())
                prefs.edit().putInt("band_$bandIndex", milliDb).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Error asignando nivel a banda $bandIndex", e)
            }

            _effectsState.value = _effectsState.value.copy(
                bands = currentBands,
                selectedPreset = "Personalizado",
            )
            prefs.edit().putString(KEY_PRESET_NAME, "Personalizado").apply()
        }
    }

    fun setBassBoost(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        prefs.edit().putInt(KEY_BASS_STRENGTH, clamped).apply()
        try {
            if (bassBoost?.strengthSupported == true) {
                bassBoost?.setStrength(clamped.toShort())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error asignando BassBoost", e)
        }
        _effectsState.value = _effectsState.value.copy(bassBoostStrength = clamped)
    }

    fun setVirtualizer(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        prefs.edit().putInt(KEY_VIRTUALIZER_STRENGTH, clamped).apply()
        try {
            if (virtualizer?.strengthSupported == true) {
                virtualizer?.setStrength(clamped.toShort())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error asignando Virtualizer", e)
        }
        _effectsState.value = _effectsState.value.copy(virtualizerStrength = clamped)
    }

    fun applyPreset(presetName: String) {
        val gains = when (presetName) {
            "Flat" -> listOf(0, 0, 0, 0, 0)
            "Synthwave" -> listOf(400, 200, -100, 300, 600)
            "Bass Boost" -> listOf(700, 500, 100, 0, 0)
            "Vocal" -> listOf(-200, 100, 400, 300, 100)
            "Cyberpunk" -> listOf(600, 300, -200, 400, 700)
            "Acoustic" -> listOf(300, 200, 0, 200, 300)
            "Electronic" -> listOf(500, 300, 0, 200, 500)
            else -> listOf(0, 0, 0, 0, 0)
        }

        val currentBands = _effectsState.value.bands.toMutableList()
        currentBands.forEachIndexed { i, band ->
            val gain = gains.getOrElse(i) { 0 }
            currentBands[i] = band.copy(currentGainMilliDb = gain)
            try {
                equalizer?.setBandLevel(band.index.toShort(), gain.toShort())
                prefs.edit().putInt("band_${band.index}", gain).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Error aplicando ganancia preset a banda $i", e)
            }
        }

        prefs.edit().putString(KEY_PRESET_NAME, presetName).apply()
        _effectsState.value = _effectsState.value.copy(
            bands = currentBands,
            selectedPreset = presetName,
        )
    }

    fun release() {
        releaseEffects()
    }

    private fun releaseEffects() {
        try {
            equalizer?.release()
            bassBoost?.release()
            virtualizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando AudioEffects", e)
        } finally {
            equalizer = null
            bassBoost = null
            virtualizer = null
        }
    }
}
