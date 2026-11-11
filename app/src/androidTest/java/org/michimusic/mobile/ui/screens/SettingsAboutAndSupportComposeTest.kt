package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme

@RunWith(AndroidJUnit4::class)
class SettingsAboutAndSupportComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun donationOpensVerifiedSponsorDestinationOnlyAfterTap() {
        var opened: String? = null
        compose.setContent {
            MichiTheme { SettingsDonationCard(onOpenExternal = { opened = it }) }
        }
        compose.runOnIdle { assertEquals(null, opened) }
        compose.onNodeWithTag("settings_donate_card").assertExists().performClick()
        compose.runOnIdle { assertEquals(SettingsSupportDestinations.SPONSORS, opened) }
    }

    @Test fun aboutIdentifiesAuthorVersionAndOpensOnlyExplicitGitHubActions() {
        var opened: String? = null
        compose.setContent {
            MichiTheme { SettingsAboutPanel(onOpenExternal = { opened = it }) }
        }
        compose.onNodeWithTag("settings_about_brand_icon").assertExists()
        compose.onNodeWithTag("settings_about_version").assertExists()
        compose.onNodeWithTag("settings_about_developer").assertExists()
        compose.runOnIdle { assertEquals(null, opened) }
        compose.onNodeWithTag("settings_about_github").performClick()
        compose.runOnIdle { assertEquals(SettingsSupportDestinations.PROFILE, opened) }
        compose.onNodeWithTag("settings_about_repository").performClick()
        compose.runOnIdle { assertEquals(SettingsSupportDestinations.REPOSITORY, opened) }
        assertFalse(SettingsSupportDestinations.isAllowed("http://github.com/sponsors/pitydah"))
    }
}
