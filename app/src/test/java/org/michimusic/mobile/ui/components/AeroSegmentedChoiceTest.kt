package org.michimusic.mobile.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AeroSegmentedChoiceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedTabRetainsRoleAndTestTag() {
        compose.setContent {
            AeroSegmentedChoice("Álbumes", selected = true, onClick = {}, testTag = "library_tab_albums")
        }
        compose.onNodeWithTag("library_tab_albums").assertIsDisplayed().assertIsSelected()
    }

    @Test fun deselectedFilterInvokesActionWithoutFalseSelection() {
        var clicks = 0
        compose.setContent {
            AeroSegmentedChoice("Lossless", selected = false, onClick = { clicks++ },
                treatment = AeroSegmentTreatment.Filter, testTag = "library_filter_lossless")
        }
        compose.onNodeWithTag("library_filter_lossless").assertIsNotSelected().performClick()
        assertEquals(1, clicks)
    }

    @Test fun touchTargetsRemainAdequateAtLargeFontScale() {
        assertEquals(48, aeroSegmentMinHeightDp(1f))
        assertEquals(56, aeroSegmentMinHeightDp(1.4f))
        assertEquals(64, aeroSegmentMinHeightDp(2f))
    }
}
