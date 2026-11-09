package org.michimusic.mobile.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VisualPreferences(
    val highContrast: Boolean = false,
    val reducedMotion: Boolean = false,
    val hapticsEnabled: Boolean = true,
    // UI5: defaults match the existing Aero appearance exactly.
    val appearanceProfile: AeroAppearanceProfile = AeroAppearanceProfile.EQUILIBRADO,
    val microtexturesEnabled: Boolean = true,
    val seekerStyle: AeroSeekerStyle = AeroSeekerStyle.ORIGINAL,
)

/**
 * Visual preferences live in SharedPreferences, which is disk. Reading them in the
 * constructor made every cold start touch the disk from the main thread while Compose
 * built the theme, and StrictMode flagged it on each launch.
 *
 * Construction is now pure: the flow starts with the deterministic defaults and [start]
 * loads the stored values on an injected dispatcher. [ready] lets the caller hold the
 * first frame until the stored theme arrives, so the app never flashes the wrong theme.
 */
class VisualPreferencesRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext: Context = context.applicationContext

    // Lazy so construction never opens the preferences file.
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("michi_settings", Context.MODE_PRIVATE)
    }

    private val _preferences = MutableStateFlow(VisualPreferences())
    val preferences: StateFlow<VisualPreferences> = _preferences.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val started = AtomicBoolean(false)

    @Volatile
    private var owner: CoroutineScope? = null

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "high_contrast" || key == "reduced_motion" || key == "haptics_enabled" ||
            key == "aero_appearance_profile" || key == "aero_microtextures_enabled" ||
            key == "aero_seeker_style"
        ) {
            // The listener fires on the thread that committed, which can be the UI thread:
            // reading the file again has to happen on IO.
            owner?.launch {
                val updated = readFromStorage()
                if (updated != null) _preferences.value = updated
            }
        }
    }

    /** Idempotent. The owner decides when the stored values are worth reading. */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        owner = scope
        scope.launch {
            val stored = readFromStorage()
            if (stored != null) {
                _preferences.value = stored
            }
            _ready.value = true
            runCatching { prefs.registerOnSharedPreferenceChangeListener(listener) }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        owner = null
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private suspend fun readFromStorage(): VisualPreferences? = withContext(ioDispatcher) {
        runCatching {
            // An unknown/corrupt profile can never prevent other preferences loading.
            val profile = runCatching {
                AeroAppearanceProfile.valueOf(prefs.getString("aero_appearance_profile", null)
                    ?: AeroAppearanceProfile.EQUILIBRADO.name)
            }.getOrDefault(AeroAppearanceProfile.EQUILIBRADO)
            val microtextures = runCatching {
                prefs.getBoolean("aero_microtextures_enabled", true)
            }.getOrDefault(true)
            val seekerStyle = savedAeroSeekerStyle(
                runCatching { prefs.getString("aero_seeker_style", null) }.getOrNull()
            )
            VisualPreferences(
                highContrast = prefs.getBoolean("high_contrast", false),
                reducedMotion = prefs.getBoolean("reduced_motion", false),
                hapticsEnabled = prefs.getBoolean("haptics_enabled", true),
                appearanceProfile = profile,
                microtexturesEnabled = microtextures,
                seekerStyle = seekerStyle,
            )
        }.getOrNull()
    }

    fun setHighContrast(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(highContrast = enabled)
        // apply() writes asynchronously; the in-memory value above is what the UI reads.
        prefs.edit { putBoolean("high_contrast", enabled) }
    }

    fun setReducedMotion(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(reducedMotion = enabled)
        prefs.edit { putBoolean("reduced_motion", enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(hapticsEnabled = enabled)
        prefs.edit { putBoolean("haptics_enabled", enabled) }
    }

    /** Reuses the single michi_settings store and its existing in-memory flow. */
    fun setAppearanceProfile(profile: AeroAppearanceProfile) {
        _preferences.value = _preferences.value.copy(appearanceProfile = profile)
        prefs.edit { putString("aero_appearance_profile", profile.name) }
    }

    fun setMicrotexturesEnabled(enabled: Boolean) {
        _preferences.value = _preferences.value.copy(microtexturesEnabled = enabled)
        prefs.edit { putBoolean("aero_microtextures_enabled", enabled) }
    }

    /** The existing StateFlow drives Now Playing without synchronous reads in Compose. */
    fun setSeekerStyle(style: AeroSeekerStyle) {
        _preferences.value = _preferences.value.copy(seekerStyle = style)
        prefs.edit { putString("aero_seeker_style", style.name) }
    }

    /** Atomic visual reset; contrast, reduced motion and haptics are NOT modified. */
    fun resetAppearance() {
        _preferences.value = _preferences.value.copy(
            appearanceProfile = AeroAppearanceProfile.EQUILIBRADO,
            microtexturesEnabled = true,
            seekerStyle = AeroSeekerStyle.ORIGINAL,
        )
        prefs.edit {
            putString("aero_appearance_profile", AeroAppearanceProfile.EQUILIBRADO.name)
            putBoolean("aero_microtextures_enabled", true)
            putString("aero_seeker_style", AeroSeekerStyle.ORIGINAL.name)
        }
    }
}
