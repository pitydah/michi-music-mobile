package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w412dp-h915dp")
class AlbumViewModeComponentsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun productionToggleRendersMosaicCoverFlowAndListContent() {
        rule.setContent {
            var mode by androidx.compose.runtime.remember { mutableStateOf(LibraryViewMode.MOSAIC) }
            Column {
                AlbumViewModeToggle(mode, onViewModeChange = { mode = it })
                AlbumPresentationContent(
                    viewMode = mode,
                    mosaicContent = { Text("Mosaico", Modifier.testTag("mode_mosaic")) },
                    coverFlowContent = { Text("CoverFlow", Modifier.testTag("mode_coverflow")) },
                    cardsContent = { Text("Tarjetas", Modifier.testTag("mode_cards")) },
                    listContent = { Text("Lista", Modifier.testTag("mode_list")) },
                )
            }
        }

        rule.onNodeWithTag("mode_mosaic").assertIsDisplayed()
        rule.onNodeWithTag("library_view_mode_toggle").performClick()
        rule.onNodeWithTag("album_mode_option_coverflow").performClick()
        rule.onNodeWithTag("mode_coverflow").assertIsDisplayed()

        rule.onNodeWithTag("library_view_mode_toggle").performClick()
        rule.onNodeWithTag("album_mode_option_cards").performClick()
        rule.onNodeWithTag("mode_cards").assertIsDisplayed()

        rule.onNodeWithTag("library_view_mode_toggle").performClick()
        rule.onNodeWithTag("album_mode_option_list").performClick()
        rule.onNodeWithTag("mode_list").assertIsDisplayed()

        rule.onNodeWithTag("library_view_mode_toggle").performClick()
        rule.onNodeWithTag("album_mode_option_mosaic").performClick()
        rule.onNodeWithTag("mode_mosaic").assertIsDisplayed()
    }
}
