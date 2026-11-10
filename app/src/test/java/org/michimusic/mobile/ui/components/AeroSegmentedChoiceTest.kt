package org.michimusic.mobile.ui.components

import android.app.Application
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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

    @Test fun compactFilterChipKeepsSelectionAndLegalTouchTarget() {
        var clicks = 0
        compose.setContent {
            AeroFilterChip(
                label = "Descargadas",
                selected = true,
                onClick = { clicks++ },
                testTag = "library_filter_downloaded",
            )
        }
        compose.onNodeWithTag("library_filter_downloaded")
            .assertIsDisplayed()
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, clicks)
    }

    @Test fun aNarrowDialogStacksInsteadOfEllipsizingItsLabels() {
        // 3 equal thirds of a 320 dp dialog cannot hold "Personalizado".
        assertEquals(true, aeroSegmentStacking(availableWidthDp = 288f, fontScale = 1f))
        assertEquals(true, aeroSegmentStacking(availableWidthDp = 300f, fontScale = 1f))
        assertEquals(true, aeroSegmentStacking(availableWidthDp = 400f, fontScale = 1.3f))
    }

    @Test fun aWideDialogKeepsTheHorizontalRail() {
        assertEquals(false, aeroSegmentStacking(availableWidthDp = 400f, fontScale = 1f))
        assertEquals(false, aeroSegmentStacking(availableWidthDp = 411f, fontScale = 1.2f))
    }
}
