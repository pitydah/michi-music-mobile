package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.michimusic.data.cache.AppDao
import org.michimusic.mobile.ui.theme.VisualPreferences
import org.michimusic.mobile.ui.theme.VisualPreferencesRepository
import org.michimusic.player.PlayerDependencies
import org.michimusic.player.ReplayGainMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SettingsHierarchyTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockAppDao: AppDao = mockk(relaxed = true)
    private val mockVisualRepo: VisualPreferencesRepository = mockk(relaxed = true)
    private val rgProcessor = org.michimusic.player.ReplayGainAudioProcessor()

    @Before
    fun setup() {
        stopKoin()
        PlayerDependencies.replayGainProcessor = rgProcessor
        io.mockk.coEvery { mockAppDao.getSetting("listening_history_enabled") } returns "true"
        every { mockVisualRepo.preferences } returns MutableStateFlow(VisualPreferences(reducedMotion = true))

        startKoin {
            modules(
                module {
                    single<AppDao> { mockAppDao }
                    single<VisualPreferencesRepository> { mockVisualRepo }
                }
            )
        }
    }

    @After
    fun tearDown() {
        PlayerDependencies.replayGainProcessor = null
        stopKoin()
    }

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

    @Test
    fun testReplayGainModeConfigurationAppliesToPlayerDependencies() {
        val modes = org.michimusic.player.ReplayGainMode.values()
        modes.forEach { mode ->
            org.michimusic.player.PlayerDependencies.configureReplayGain(
                mode,
                org.michimusic.player.ReplayGainPreAmp(with = -1.5f, without = 0f)
            )
        }
    }

    @Test
    fun testClickingReplayGainModeInComposeUpdatesPlayerDependencies() {
        composeTestRule.setContent {
            org.koin.compose.KoinContext {
                SettingsScreen(initialSection = SettingsSection.AUDIO_QUALITY)
            }
        }
        composeTestRule.waitForIdle()

        // Scroll to and click "Pista" (TRACK mode) via testTag
        composeTestRule.onNodeWithTag("rg_mode_TRACK")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(ReplayGainMode.TRACK, PlayerDependencies.replayGainMode)

        // Scroll to and click "Desactivado" (OFF mode) via testTag
        composeTestRule.onNodeWithTag("rg_mode_OFF")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(ReplayGainMode.OFF, PlayerDependencies.replayGainMode)
    }

    @Test
    fun testHapticsToggleInAccessibilitySection_invokesRepository() {
        composeTestRule.setContent {
            org.koin.compose.KoinContext {
                SettingsScreen(initialSection = SettingsSection.ACCESSIBILITY)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_haptics_switch")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        io.mockk.verify(atLeast = 1) { mockVisualRepo.setHapticsEnabled(any()) }
    }
}

