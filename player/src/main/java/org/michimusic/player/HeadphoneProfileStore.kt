package org.michimusic.player

import android.content.Context
import android.media.AudioDeviceInfo
import org.michimusic.core.models.AudioProcessingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The label is user-supplied. No passive headphone model is auto-detected through USB. */
const val MICHI_PEQ_BAND_COUNT = 6

data class PeqBand(val frequencyHz: Float, val gainDb: Float, val q: Float) {
    fun safe() = PeqBand(
        frequencyHz.takeIf(Float::isFinite)?.coerceIn(20f, 20_000f) ?: 1000f,
        gainDb.takeIf(Float::isFinite)?.coerceIn(-12f, 12f) ?: 0f,
        q.takeIf(Float::isFinite)?.coerceIn(0.3f, 8f) ?: 1f,
    )
}

data class HeadphoneProfileState(
    val enabled: Boolean = false,
    val headphoneName: String = "Mis auriculares",
    val preset: String = "Neutral",
    val bands: List<PeqBand> = defaultPeqBands(),
    /** Deliberately scoped to the selected Android route; never a USB hardware identity. */
    val selectedRouteId: Int? = null,
    /** Observed route fingerprint at explicit opt-in; ephemeral, never persisted. */
    val selectedObservedAtMs: Long? = null,
    /** Legacy R5 could silently truncate six filters to three. Inform the user without fabricating the missing bands. */
    val legacyBandsTruncated: Boolean = false,
)

fun defaultPeqBands() = listOf(
    PeqBand(60f, 0f, 0.8f), PeqBand(180f, 0f, 0.8f), PeqBand(800f, 0f, 1f),
    PeqBand(2400f, 0f, 1f), PeqBand(6500f, 0f, 1f), PeqBand(12000f, 0f, 1f),
)

/** These are listening preferences, not measured headphone frequency-response correction. */
fun peqPresetBands(preset: String): List<PeqBand> = when (preset) {
    "Warm" -> listOf(PeqBand(60f, 1f, 0.7f), PeqBand(180f, 1.5f, 0.75f), PeqBand(800f, 0f, 1f),
        PeqBand(2400f, -0.5f, 1f), PeqBand(6500f, -1f, 1f), PeqBand(12000f, 0f, 1f))
    "Voice" -> listOf(PeqBand(60f, -0.8f, 0.9f), PeqBand(180f, -0.5f, 0.9f), PeqBand(800f, 0f, 1f),
        PeqBand(2400f, 1.5f, 0.9f), PeqBand(6500f, 0.5f, 1f), PeqBand(12000f, 0f, 1f))
    else -> defaultPeqBands()
}

/** A disconnected/merely requested route must NEVER apply personal EQ to the phone speaker. */
fun headphonePeqEligible(config: HeadphoneProfileState, route: AudioRouteStatus?, mode: AudioProcessingMode): Boolean {
    if (!config.enabled || mode != AudioProcessingMode.DSP) return false
    val device = (route as? AudioRouteStatus.Confirmed)?.device ?: return false
    if (config.selectedRouteId != device.androidDeviceId ||
        config.selectedObservedAtMs != route.observedAtMs) return false
    return device.androidType in setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    )
}

/**
 * Whether the Headphone Studio switch may be operated right now.
 *
 * Turning the profile off must always be possible: an enabled profile has to be
 * switchable even while the headset is unplugged, otherwise the only way to
 * disable it would be to plug the headset back in.
 */
fun headphoneSwitchEnabled(
    config: HeadphoneProfileState,
    mode: AudioProcessingMode,
    legacyBandsTruncated: Boolean,
    hasHeadphone: Boolean,
): Boolean = config.enabled ||
    (hasHeadphone && mode == AudioProcessingMode.DSP && !legacyBandsTruncated)

/** Conservative upper bound for EQ boost; provides at least this much headroom before filters. */
fun peqHeadroomDb(bands: List<PeqBand>): Float =
    // Every enabled boost may overlap. Six bands at +12 dB need 72 dB of
    // conservative headroom; silently capping at 36 dB cannot guarantee it.
    bands.sumOf { it.safe().gainDb.coerceAtLeast(0f).toDouble() }.toFloat()
        .coerceIn(0f, MICHI_PEQ_BAND_COUNT * 12f)

class HeadphoneProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("michi_headphone_profiles_v1", Context.MODE_PRIVATE)
    private val _savedProfiles = MutableStateFlow(prefs.getStringSet("saved_profiles", emptySet()).orEmpty().sorted())
    val savedProfiles: StateFlow<List<String>> = _savedProfiles.asStateFlow()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<HeadphoneProfileState> = _state.asStateFlow()

    private fun decodeBands(value: String?): List<PeqBand>? {
        if (value.isNullOrBlank() || value.length > 4096) return null
        val rows = value.split(';')
        if (rows.size !in 3..MICHI_PEQ_BAND_COUNT) return null
        return rows.map { row ->
            val tokens = row.split(',')
            if (tokens.size != 3) return null
            val frequency = tokens[0].toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
            val gain = tokens[1].toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
            val q = tokens[2].toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
            PeqBand(frequency, gain, q).safe()
        }
    }

    private fun encodeBands(bands: List<PeqBand>): String =
        bands.joinToString(";") { "${it.frequencyHz},${it.gainDb},${it.q}" }

    private fun load(): HeadphoneProfileState {
        val preset = prefs.getString("preset", "Neutral")?.takeIf {
            it in setOf("Neutral", "Warm", "Voice", "Custom")
        } ?: "Neutral"
        val v2 = prefs.getString("profile_v2", null)
        // One preferences entry is the atomic v2 snapshot. A legacy 3-band payload
        // remains readable but is NOT padded with fictitious corrections.
        val snapshot = v2?.split('|', limit = 4)?.takeIf { it.size == 4 && it[0] == "2" }
        val bandsFromV2 = snapshot?.let { decodeBands(it[3]) }
        val bandsFromV1 = if (snapshot == null) decodeBands(prefs.getString("bands", null)) else null
        val parsed = bandsFromV2 ?: bandsFromV1
        // A v2 snapshot without the full bank is as incomplete as the legacy
        // three-band payload: keep it disabled AND tell the user why, instead of
        // leaving an inert profile with no explanation.
        val legacyTruncated = (snapshot == null && parsed?.size == 3) ||
            (snapshot != null && bandsFromV2?.size != MICHI_PEQ_BAND_COUNT)
        return HeadphoneProfileState(
            enabled = if (snapshot != null) snapshot[1] == "true" && bandsFromV2?.size == MICHI_PEQ_BAND_COUNT
                else prefs.getBoolean("enabled", false) && !legacyTruncated,
            headphoneName = (prefs.getString("headphone", "Mis auriculares") ?: "Mis auriculares").take(80),
            preset = preset,
            bands = parsed ?: peqPresetBands(preset),
            selectedRouteId = null, // Android route IDs are ephemeral: always explicitly reconfirm.
            legacyBandsTruncated = legacyTruncated,
        )
    }

    private fun commit(next: HeadphoneProfileState, persist: Boolean = true) {
        // Never truncate an edited or saved profile. A partial legacy payload
        // remains disabled until the user deliberately chooses a complete preset.
        require(next.bands.size == MICHI_PEQ_BAND_COUNT) {
            "Incomplete PEQ profile: expected $MICHI_PEQ_BAND_COUNT bands, got ${next.bands.size}"
        }
        val safe = next.copy(bands = next.bands.map(PeqBand::safe), legacyBandsTruncated = false)
        if (persist) {
            val snapshot = "2|${safe.enabled}|${safe.preset}|${encodeBands(safe.bands)}"
            prefs.edit().putString("profile_v2", snapshot)
                .putBoolean("enabled", safe.enabled)
                .putString("headphone", safe.headphoneName.take(80))
                .putString("preset", safe.preset)
                .putString("bands", encodeBands(safe.bands))
                .apply()
        }
        _state.value = safe
    }


    /** Enabled state requires an explicit route selection on every new USB enumeration. */
    fun selectRoute(id: Int?, observedAtMs: Long? = null) {
        // A reconnected DAC can reuse an Android integer ID. Only a currently confirmed
        // observation explicitly chosen by the user authorizes the personal profile.
        if (_state.value.bands.size != MICHI_PEQ_BAND_COUNT) return
        commit(_state.value.copy(selectedRouteId = id, selectedObservedAtMs = observedAtMs,
            enabled = id != null && observedAtMs != null))
    }
    fun enable(enabled: Boolean) {
        if (_state.value.bands.size == MICHI_PEQ_BAND_COUNT) commit(_state.value.copy(enabled = enabled))
    }
    fun rename(name: String) {
        val fixed = name.trim().take(80).ifEmpty { "Mis auriculares" }
        if (_state.value.bands.size == MICHI_PEQ_BAND_COUNT) commit(_state.value.copy(headphoneName = fixed))
        else _state.value = _state.value.copy(headphoneName = fixed)
    }
    fun selectPreset(preset: String) {
        if (preset !in setOf("Neutral", "Warm", "Voice")) return
        commit(_state.value.copy(preset = preset, bands = peqPresetBands(preset)))
    }
    fun editBand(index: Int, band: PeqBand, persist: Boolean = false) {
        if (index !in 0 until MICHI_PEQ_BAND_COUNT || _state.value.bands.size != MICHI_PEQ_BAND_COUNT) return
        commit(_state.value.copy(preset = "Custom", bands = _state.value.bands.toMutableList().apply { this[index] = band.safe() }), persist)
    }

    fun persistCurrent() {
        if (_state.value.bands.size == MICHI_PEQ_BAND_COUNT) commit(_state.value)
    }

    /** Model names are user-entered; no claim of measured correction. */
    fun saveNamedProfile() {
        val name = _state.value.headphoneName.trim().take(80)
            .replace('|', '_').replace(';', '_').replace(',', '_')
        if (name.isEmpty() || _state.value.bands.size != MICHI_PEQ_BAND_COUNT) return
        val names = (_savedProfiles.value + name).distinct().take(20)
        prefs.edit().putStringSet("saved_profiles", names.toSet())
            .putString("named_$name", encodeBands(_state.value.bands))
            .apply()
        _savedProfiles.value = names.sorted()
    }

    fun loadNamedProfile(name: String) {
        if (name !in _savedProfiles.value) return
        val bands = decodeBands(prefs.getString("named_$name", null))
            ?.takeIf { it.size == MICHI_PEQ_BAND_COUNT } ?: return
        commit(_state.value.copy(headphoneName = name, preset = "Custom", bands = bands))
    }
}
