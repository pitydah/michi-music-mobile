package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.michimusic.data.cache.AppDao

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MichiIntelligenceSettingsUiTest {
    @get:Rule val composeRule = createComposeRule()
    private val dao = mockk<AppDao>(relaxed = true)
    private lateinit var koinApp: KoinApplication

    @Before fun setup() {
        coEvery { dao.getSetting("listening_history_enabled") } returns "true"
        koinApp = koinApplication { modules(module { single<AppDao> { dao } }) }
    }

    @After fun teardown() { koinApp.close() }

    @Test fun intelligenceAndPrivacyModesAreAvailableWithoutProviderConnection() {
        composeRule.setContent { KoinIsolatedContext(koinApp) { SettingsPrivacyPanel() } }
        composeRule.onNodeWithTag("settings_intelligence_learning").assertExists()
        composeRule.onNodeWithTag("settings_intelligence_privacy_maximum").assertExists()
        composeRule.onNodeWithTag("settings_intelligence_privacy_limited_sharing").assertExists()
        composeRule.onNodeWithTag("settings_intelligence_privacy_custom_sharing").assertExists()
        composeRule.onNodeWithTag("settings_intelligence_provider_off").assertExists()
    }

    @Test fun eraseActionRequiresConfirmation() {
        composeRule.setContent { KoinIsolatedContext(koinApp) { SettingsPrivacyPanel() } }
        composeRule.onNodeWithTag("settings_intelligence_erase").assertExists()
        composeRule.onNodeWithTag("settings_intelligence_erase").performClick()
        composeRule.onNodeWithTag("settings_intelligence_erase_confirm").assertExists()
    }
}
