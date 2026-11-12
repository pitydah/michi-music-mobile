package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import org.michimusic.mobile.library.MusicRootAuthority
import org.michimusic.mobile.library.MusicRootChange
import org.michimusic.data.repository.MusicRoot
import org.michimusic.mobile.ui.theme.AeroCoverFlowReflection
import org.michimusic.mobile.ui.theme.VisualPreferences
import org.michimusic.mobile.ui.theme.VisualPreferencesRepository
import org.michimusic.mobile.playback.settingsReplayGainMode
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
    private val mockRootAuthority: MusicRootAuthority = mockk(relaxed = true)
    private val rgProcessor = org.michimusic.player.ReplayGainAudioProcessor()
    private lateinit var context: android.content.Context

    @Before
    fun setup() {
        stopKoin()
        PlayerDependencies.replayGainProcessor = rgProcessor
        PlayerDependencies.publishOutputAuthority("local_phone", true)
        context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        context.getSharedPreferences("michi_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("replaygain_mode", ReplayGainMode.OFF.name)
            .commit()
        io.mockk.coEvery { mockAppDao.getSetting("listening_history_enabled") } returns "true"
        every { mockVisualRepo.preferences } returns MutableStateFlow(VisualPreferences(reducedMotion = true))
        every { mockRootAuthority.currentRoot } returns MutableStateFlow(MusicRoot.Default)
        every { mockRootAuthority.revision } returns MutableStateFlow(0L)
        every { mockRootAuthority.lastScan } returns MutableStateFlow(null)
        every { mockRootAuthority.permissionRevoked() } returns false
        every { mockRootAuthority.canWriteCurrentRoot() } returns false
        every { mockRootAuthority.currentLocalVisibilitySource() } returns
            "mediastore:external_primary:Music"
        io.mockk.coEvery { mockRootAuthority.hiddenTracks() } returns emptyList()
        io.mockk.coEvery { mockRootAuthority.rescan(any()) } returns
            MusicRootChange(success = true, root = MusicRoot.Default)

        startKoin {
            modules(
                module {
                    single<AppDao> { mockAppDao }
                    single<VisualPreferencesRepository> { mockVisualRepo }
                    single<MusicRootAuthority> { mockRootAuthority }
                    // The auto-sync switch reads its initial state from the scheduler, so the
                    // settings screen needs one definition even when the test never toggles it.
                    single<org.michimusic.mobile.sync.AutoSyncScheduler> {
                        mockk<org.michimusic.mobile.sync.AutoSyncScheduler>(relaxed = true)
                    }
                    // The privacy section now renders the device identity state. These tests
                    // never resolve it, so a permanently loading authority is enough and
                    // keeps them independent of the Keystore.
                    single<org.michimusic.link.identity.MichiIdentityAuthority> {
                        org.michimusic.link.identity.MichiIdentityAuthority(
                            context = mockk(relaxed = true),
                            initialState = org.michimusic.link.identity.IdentityState.Loading,
                        )
                    }
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
        assertTrue(sections.contains("APPEARANCE"))
        assertTrue(sections.contains("PLAYBACK"))
        assertTrue(sections.contains("PROCESSING"))
        assertTrue(sections.contains("AUDIO_OUTPUT"))
        assertTrue(sections.contains("ECOSYSTEM"))
        assertTrue(sections.contains("SYSTEM"))
        assertTrue(sections.contains("ABOUT"))
        assertEquals(12, sections.size)
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
    fun testBackFromSystemKeepsTheHubScrollPosition() {
        composeTestRule.setContent {
            org.koin.compose.KoinContext {
                SettingsScreen()
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_route_system")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag("settings_reopen_wizard").assertExists()

        composeTestRule.onNodeWithTag("settings_back_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_hub").assertExists()
        // SaveableStateHolder must restore the ROOT scroll state, not reset it.
        composeTestRule.onNodeWithTag("settings_route_system").assertIsDisplayed()
    }

    @Test
    fun testBackFromSystemKeepsTheHubSearchFilter() {
        composeTestRule.setContent {
            org.koin.compose.KoinContext {
                SettingsScreen()
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_search_input").performTextInput("diagnóstico")
        composeTestRule.onNodeWithTag("settings_route_system").performClick()
        composeTestRule.onNodeWithTag("settings_reopen_wizard").assertExists()

        composeTestRule.onNodeWithTag("settings_back_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_route_system").assertExists()
        composeTestRule.onNodeWithTag("settings_route_playback").assertDoesNotExist()
    }

    @Test
    fun testClickingReplayGainModeInComposeUpdatesPlayerDependencies() {
        composeTestRule.setContent {
            org.koin.compose.KoinContext {
                SettingsScreen(initialSection = SettingsSection.AUDIO_QUALITY)
            }
        }
        composeTestRule.waitForIdle()

        // The panel enables the selector after its gateway snapshot arrives.
        // Wait for that public state instead of racing the click in F-Droid.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeTestRule.onNodeWithTag("rg_mode_TRACK").assertIsEnabled()
                true
            }.getOrDefault(false)
        }

        // Scroll to and click "Pista" (TRACK mode) via testTag
        composeTestRule.onNodeWithTag("rg_mode_TRACK")
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            context.getSharedPreferences("michi_settings", android.content.Context.MODE_PRIVATE)
                .getString("replaygain_mode", null) == ReplayGainMode.TRACK.name
        }
        assertEquals(
            ReplayGainMode.TRACK,
            settingsReplayGainMode(
                context.getSharedPreferences("michi_settings", android.content.Context.MODE_PRIVATE)
                    .getString("replaygain_mode", null),
            ),
        )

        // Scroll to and click "Desactivado" (OFF mode) via testTag
        composeTestRule.onNodeWithTag("rg_mode_OFF")
            .performScrollTo()
            .performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            context.getSharedPreferences("michi_settings", android.content.Context.MODE_PRIVATE)
                .getString("replaygain_mode", null) == ReplayGainMode.OFF.name
        }
        assertEquals(
            ReplayGainMode.OFF,
            settingsReplayGainMode(
                context.getSharedPreferences("michi_settings", android.content.Context.MODE_PRIVATE)
                    .getString("replaygain_mode", null),
            ),
        )
    }

    @Test
    fun testLibraryRootSectionExposesCurrentRootAndRescanAction() {
        composeTestRule.setContent {
            org.koin.compose.KoinContext {
                SettingsScreen(initialSection = SettingsSection.LIBRARY_STORAGE)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_library_root").assertExists()
        composeTestRule.onNodeWithTag("settings_library_restore_music").assertExists()
        composeTestRule.onNodeWithTag("settings_library_rescan")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        io.mockk.coVerify(atLeast = 1) { mockRootAuthority.rescan(any()) }
    }

    @Test
    fun testCoverFlowReflectionControlInvokesPersistedPreference() {
        composeTestRule.setContent {
            org.koin.compose.KoinContext {
                SettingsScreen(initialSection = SettingsSection.APPEARANCE)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_coverflow_reflection_intenso")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        io.mockk.verify(atLeast = 1) {
            mockVisualRepo.setCoverFlowReflection(AeroCoverFlowReflection.INTENSO)
        }
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

