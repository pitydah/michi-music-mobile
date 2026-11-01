package org.michimusic.mobile.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VisualPreferences(
    val highContrast: Boolean = false,
    val reducedMotion: Boolean = false,
    val hapticsEnabled: Boolean = true,
)

class VisualPreferencesRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("michi_settings", Context.MODE_PRIVATE)

    private val _preferences = MutableStateFlow(
        VisualPreferences(
            highContrast = prefs.getBoolean("high_contrast", false),
            reducedMotion = prefs.getBoolean("reduced_motion", false),
            hapticsEnabled = prefs.getBoolean("haptics_enabled", true),
        )
    )
    val preferences: StateFlow<VisualPreferences> = _preferences.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "high_contrast" || key == "reduced_motion" || key == "haptics_enabled") {
            _preferences.value = VisualPreferences(
                highContrast = prefs.getBoolean("high_contrast", false),
                reducedMotion = prefs.getBoolean("reduced_motion", false),
                hapticsEnabled = prefs.getBoolean("haptics_enabled", true),
            )
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun setHighContrast(enabled: Boolean) {
        prefs.edit().putBoolean("high_contrast", enabled).apply()
        _preferences.value = _preferences.value.copy(highContrast = enabled)
    }

    fun setReducedMotion(enabled: Boolean) {
        prefs.edit().putBoolean("reduced_motion", enabled).apply()
        _preferences.value = _preferences.value.copy(reducedMotion = enabled)
    }

    fun setHapticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("haptics_enabled", enabled).apply()
        _preferences.value = _preferences.value.copy(hapticsEnabled = enabled)
    }
}
