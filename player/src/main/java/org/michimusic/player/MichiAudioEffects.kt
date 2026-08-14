package org.michimusic.player

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
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
private const val KEY_LOUDNESS_GAIN = "loudness_gain"
private const val KEY_PREAMP_GAIN = "preamp_gain"
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
    val selectedPreset: String = "Michi Signature",
    val preampGainDb: Float = 0f, // -12dB to +12dB
    val bassBoostStrength: Int = 300, // 0 to 1000
    val virtualizerStrength: Int = 150, // 0 to 1000
    val loudnessGainMb: Int = 0, // 0 to 1000 mB (0 to +10 dB)
    val bands: List<EqualizerBandState> = emptyList(),
)

class MichiAudioEffects(
    private val context: Context,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(EQ_PREFS, Context.MODE_PRIVATE)

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
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

        val isMasterEnabled = prefs.getBoolean(KEY_EQ_ENABLED, true)

        try {
            equalizer = Equalizer(0, sessionId).apply {
                enabled = isMasterEnabled
            }

            bassBoost = BassBoost(0, sessionId).apply {
                enabled = isMasterEnabled
                val str = prefs.getInt(KEY_BASS_STRENGTH, 300).toShort()
                if (strengthSupported) setStrength(str)
            }

            virtualizer = Virtualizer(0, sessionId).apply {
                enabled = isMasterEnabled
                val str = prefs.getInt(KEY_VIRTUALIZER_STRENGTH, 150).toShort()
                if (strengthSupported) setStrength(str)
            }

            loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                enabled = isMasterEnabled
                val gain = prefs.getInt(KEY_LOUDNESS_GAIN, 0)
                setTargetGain(gain)
            }

            syncBandsFromHardware()
            applySavedBandGains()
            Log.i(TAG, "AudioEffects vinculados con éxito a sesión $sessionId")
        } catch (e: Exception) {
            Log.w(TAG, "Hardware effects initialization fallback", e)
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
                preampGainDb = prefs.getFloat(KEY_PREAMP_GAIN, 0f),
                bassBoostStrength = prefs.getInt(KEY_BASS_STRENGTH, 300),
                virtualizerStrength = prefs.getInt(KEY_VIRTUALIZER_STRENGTH, 150),
                loudnessGainMb = prefs.getInt(KEY_LOUDNESS_GAIN, 0),
                selectedPreset = prefs.getString(KEY_PRESET_NAME, "Michi Signature") ?: "Michi Signature",
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
            preampGainDb = prefs.getFloat(KEY_PREAMP_GAIN, 0f),
            bassBoostStrength = prefs.getInt(KEY_BASS_STRENGTH, 300),
            virtualizerStrength = prefs.getInt(KEY_VIRTUALIZER_STRENGTH, 150),
            loudnessGainMb = prefs.getInt(KEY_LOUDNESS_GAIN, 0),
            selectedPreset = prefs.getString(KEY_PRESET_NAME, "Michi Signature") ?: "Michi Signature",
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
            loudnessEnhancer?.enabled = enabled
        } catch (e: Exception) {
            Log.w(TAG, "Error cambiando estado de efectos", e)
        }
        _effectsState.value = _effectsState.value.copy(isEnabled = enabled)
    }

    fun setBandGain(bandIndex: Int, milliDb: Int) {
        val currentBands = _effectsState.value.bands.toMutableList()
        val targetIdx = currentBands.indexOfFirst { it.index == bandIndex }
        if (targetIdx != -1) {
            val clamped = milliDb.coerceIn(currentBands[targetIdx].minGainMilliDb, currentBands[targetIdx].maxGainMilliDb)
            val updatedBand = currentBands[targetIdx].copy(currentGainMilliDb = clamped)
            currentBands[targetIdx] = updatedBand

            try {
                equalizer?.setBandLevel(bandIndex.toShort(), clamped.toShort())
                prefs.edit().putInt("band_$bandIndex", clamped).apply()
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

    fun setPreamp(gainDb: Float) {
        val clamped = gainDb.coerceIn(-12f, 12f)
        prefs.edit().putFloat(KEY_PREAMP_GAIN, clamped).apply()
        _effectsState.value = _effectsState.value.copy(preampGainDb = clamped)
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

    fun setLoudness(gainMb: Int) {
        val clamped = gainMb.coerceIn(0, 1000)
        prefs.edit().putInt(KEY_LOUDNESS_GAIN, clamped).apply()
        try {
            loudnessEnhancer?.setTargetGain(clamped)
        } catch (e: Exception) {
            Log.w(TAG, "Error asignando LoudnessEnhancer", e)
        }
        _effectsState.value = _effectsState.value.copy(loudnessGainMb = clamped)
    }

    fun resetToFlat() {
        val currentBands = _effectsState.value.bands.toMutableList()
        currentBands.forEachIndexed { i, band ->
            currentBands[i] = band.copy(currentGainMilliDb = 0)
            try {
                equalizer?.setBandLevel(band.index.toShort(), 0)
                prefs.edit().putInt("band_${band.index}", 0).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Error reseteando banda $i", e)
            }
        }
        prefs.edit().putString(KEY_PRESET_NAME, "Flat / Plano").apply()
        prefs.edit().putFloat(KEY_PREAMP_GAIN, 0f).apply()
        _effectsState.value = _effectsState.value.copy(
            bands = currentBands,
            selectedPreset = "Flat / Plano",
            preampGainDb = 0f,
        )
    }

    fun applyPreset(presetName: String) {
        val gains = when (presetName) {
            "Flat / Plano" -> listOf(0, 0, 0, 0, 0)
            "Michi Signature" -> listOf(400, 200, -100, 250, 500)
            "Bass Punch" -> listOf(700, 450, 0, 100, 200)
            "Treble Clarity" -> listOf(-100, 100, 300, 550, 700)
            "Vocal Presence" -> listOf(-200, 100, 500, 300, 100)
            "Cyberpunk EDM" -> listOf(600, 350, -200, 400, 650)
            "Acoustic Hi-Fi" -> listOf(200, 200, 100, 300, 400)
            "Rock & Metal" -> listOf(500, 250, -150, 350, 500)
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
            loudnessEnhancer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando AudioEffects", e)
        } finally {
            equalizer = null
            bassBoost = null
            virtualizer = null
            loudnessEnhancer = null
        }
    }
}
