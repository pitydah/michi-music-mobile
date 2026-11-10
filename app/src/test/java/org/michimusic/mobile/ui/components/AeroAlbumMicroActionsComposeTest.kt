package org.michimusic.mobile.ui.components

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AeroAlbumMicroActionsComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allThreeActionsHaveIndependentTouchTargetsAndCallbacks() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MichiTheme {
                AeroAlbumMicroActions(
                    onPlay = { actions += "play" },
                    onShuffle = { actions += "shuffle" },
                    onAddToPlaylist = { actions += "add" },
                )
            }
        }
        compose.onNodeWithTag("album_detail_play")
            .assertContentDescriptionEquals("Reproducir álbum")
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("album_detail_shuffle")
            .assertContentDescriptionEquals("Reproducir álbum aleatoriamente")
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("album_detail_add_to_playlist")
            .assertContentDescriptionEquals("Añadir álbum a una lista")
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .performClick()
        assertEquals(listOf("play", "shuffle", "add"), actions)
    }

    @Test fun unavailableTracksDoNotTriggerPlaybackOrPlaylist() {
        var count = 0
        compose.setContent {
            MichiTheme {
                AeroAlbumMicroActions(
                    onPlay = { count++ }, onShuffle = { count++ },
                    onAddToPlaylist = { count++ },
                    playEnabled = false, shuffleEnabled = false,
                    addToPlaylistEnabled = false,
                )
            }
        }
        compose.onNodeWithTag("album_detail_play").assertIsNotEnabled()
        compose.onNodeWithTag("album_detail_shuffle").assertIsNotEnabled()
        compose.onNodeWithTag("album_detail_add_to_playlist").assertIsNotEnabled()
        assertEquals(0, count)
    }
}
