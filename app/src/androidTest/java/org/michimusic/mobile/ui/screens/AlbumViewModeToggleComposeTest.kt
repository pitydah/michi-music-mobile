package org.michimusic.mobile.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme

@RunWith(AndroidJUnit4::class)
class AlbumViewModeToggleComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun popupPreservesFourViewsAndExactSelection() {
        val mode = mutableStateOf(LibraryViewMode.MOSAIC)
        var selections = 0
        rule.setContent {
            MichiTheme {
                AlbumViewModeToggle(
                    viewMode = mode.value,
                    onViewModeChange = { mode.value = it; selections++ },
                )
            }
        }
        rule.onNodeWithTag("library_view_mode_toggle").performClick()
        rule.onNodeWithTag("library_view_mode_menu").assertExists()
        listOf("mosaic", "coverflow", "cards", "list").forEach { key ->
            rule.onNodeWithTag("album_mode_option_$key").assertExists()
        }
        rule.onNodeWithTag("album_mode_option_cards").performClick()
        rule.runOnIdle {
            assertEquals(LibraryViewMode.CARDS, mode.value)
            assertEquals(1, selections)
        }
        rule.onNodeWithTag("library_view_mode_menu").assertDoesNotExist()
    }
}
