package org.michimusic.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeNavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun verifyNavigationFlowAcrossAllDestinations() {
        // 1. MainActivity starts & Home is displayed
        composeTestRule.waitForIdle()

        // 2. Bottom navigation exists and shows tabs
        composeTestRule.onNodeWithTag("bottom_nav_bar").assertExists()

        // 3. Navigate to Library (Albums/Tracks)
        composeTestRule.onNodeWithTag("nav_tab_library").performClick()
        composeTestRule.waitForIdle()

        // 4. Navigate to Remote tab
        composeTestRule.onNodeWithTag("nav_tab_remote").performClick()
        composeTestRule.waitForIdle()

        // 5. Navigate to Sync tab
        composeTestRule.onNodeWithTag("nav_tab_sync").performClick()
        composeTestRule.waitForIdle()

        // 6. Navigate to Settings tab
        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()

        // 7. Navigate back to Home
        composeTestRule.onNodeWithTag("nav_tab_home").performClick()
        composeTestRule.waitForIdle()
    }
}
