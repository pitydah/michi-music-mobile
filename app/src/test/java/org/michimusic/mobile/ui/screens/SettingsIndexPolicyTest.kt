package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIndexPolicyTest {
    @Test fun exactlyTenFunctionalDestinations() {
        assertEquals(10, settingsIndexDestinations.size)
        assertEquals(10, settingsIndexDestinations.map { it.section }.distinct().size)
        assertFalse(settingsIndexDestinations.any { it.section == SettingsSection.AUDIO_QUALITY })
    }
    @Test fun everyDestinationHasCategory() {
        assertEquals(listOf(3,2,3,2), SettingsIndexGroup.entries.map { group ->
            settingsIndexDestinations.count { it.group == group }
        })
    }
    @Test fun searchIsAccentInsensitive() {
        assertEquals(SettingsSection.PROCESSING,
            filterSettingsDestinations("ecualizacion").single().section)
        assertEquals(SettingsSection.PRIVACY,
            filterSettingsDestinations("privacidad").single().section)
    }
    @Test fun searchFindsKeywordsWithoutReadingAnyLibrary() {
        assertEquals(SettingsSection.AUDIO_OUTPUT,
            filterSettingsDestinations("bluetooth").single().section)
        assertEquals(SettingsSection.ECOSYSTEM,
            filterSettingsDestinations("aircat").single().section)
    }
    @Test fun searchesAreIndependentOfCaseAndWhitespace() {
        assertEquals(SettingsSection.LIBRARY_STORAGE,
            filterSettingsDestinations("  SAF  ").single().section)
    }
    @Test fun emptySearchReturnsAllInCanonicalOrder() {
        assertEquals(settingsIndexDestinations, filterSettingsDestinations("  "))
    }
    @Test fun unknownSearchDoesNotInventDestinations() {
        assertTrue(filterSettingsDestinations("nonexistentsetting123").isEmpty())
    }
    @Test fun aboutIsTheLastNavigationDestinationAndDonationIsNotNavigation() {
        assertEquals(SettingsSection.ABOUT, settingsIndexDestinations.last().section)
        assertTrue(settingsIndexDestinations.none { it.title == "Donar" })
    }
    @Test fun legacyAudioRouteRemainsAddressableButHiddenFromIndex() {
        assertTrue(SettingsSection.entries.contains(SettingsSection.AUDIO_QUALITY))
        assertFalse(settingsIndexDestinations.any { it.section == SettingsSection.AUDIO_QUALITY })
    }
    @Test fun allSectionsHaveStableDisplayNamesWithoutTouchingRepositories() {
        SettingsSection.entries.forEach { route ->
            assertTrue(settingsDestinationTitle(route).isNotBlank())
        }
        assertEquals("Reproducción", settingsDestinationTitle(SettingsSection.AUDIO_QUALITY))
        assertEquals("Reproducción", settingsDestinationTitle(SettingsSection.PLAYBACK))
    }

}
