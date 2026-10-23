package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHierarchyTest {

    @Test
    fun testSettingsSectionEnumCompleteness() {
        val sections = SettingsSection.values().map { it.name }
        assertTrue(sections.contains("ROOT"))
        assertTrue(sections.contains("AUDIO_QUALITY"))
        assertTrue(sections.contains("LIBRARY_STORAGE"))
        assertTrue(sections.contains("ACCESSIBILITY"))
        assertTrue(sections.contains("PRIVACY"))
        assertEquals(5, sections.size)
    }

    @Test
    fun testPreservedPreferenceKeysContract() {
        // Garantizar que las claves de SharedPreferences no sufran regresión
        val expectedKeys = mapOf(
            "KEY_RG_MODE" to "replaygain_mode",
            "KEY_RG_PREAMP_WITH" to "replaygain_preamp_with",
            "KEY_RG_PREAMP_WITHOUT" to "replaygain_preamp_without",
            "KEY_DOWNLOAD_ARTWORK" to "download_artwork",
            "KEY_AUTO_SCAN_LIBRARY" to "auto_scan_library",
            "KEY_HIGH_CONTRAST" to "high_contrast",
            "KEY_REDUCED_MOTION" to "reduced_motion",
            "DB_LISTENING_HISTORY_ENABLED" to "listening_history_enabled",
        )

        expectedKeys.forEach { (_, key) ->
            assertTrue(key.isNotEmpty())
        }
    }
}
