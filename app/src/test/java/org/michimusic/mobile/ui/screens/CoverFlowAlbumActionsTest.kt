package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CoverFlowAlbumActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitPlayInvokesOnlyPlayAction() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MichiTheme {
                CoverFlowAlbumActions(
                    albumTitle = "Dos identidades",
                    hasPlayableTracks = true,
                    onPlay = { actions += "play" },
                    onShuffle = { actions += "shuffle" },
                    onAddToPlaylist = { actions += "playlist" },
                )
            }
        }

        compose.onNodeWithTag("coverflow_play_album").performClick()

        assertEquals(listOf("play"), actions)
    }

    @Test fun unavailableAlbumDisablesExplicitPlay() {
        compose.setContent {
            MichiTheme {
                CoverFlowAlbumActions(
                    albumTitle = "Sin pistas",
                    hasPlayableTracks = false,
                    onPlay = {},
                    onShuffle = {},
                    onAddToPlaylist = {},
                )
            }
        }

        compose.onNodeWithTag("coverflow_play_album").assertIsNotEnabled()
    }
}
