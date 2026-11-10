package org.michimusic.mobile.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.screens.HomeHeroItem
import org.michimusic.mobile.ui.screens.HomeHeroKind
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AeroHomeHeroCarouselTest {
    @get:Rule val compose = createComposeRule()

    private val items = listOf(
        HomeHeroItem(
            key = "track:t1", kind = HomeHeroKind.TRACK, sourceId = "t1",
            title = "First Song", subtitle = "Artist A", meta = "2 canciones",
            coverIds = listOf("cover"), fallbackMediaUri = null,
            trackIds = listOf("t1"), primaryLabel = "Escuchar",
        ),
        HomeHeroItem(
            key = "album:a1", kind = HomeHeroKind.ALBUM, sourceId = "a1",
            title = "Album A", subtitle = "Artist A", meta = "2 canciones · Álbum",
            coverIds = listOf("cover"), fallbackMediaUri = null,
            trackIds = listOf("t1", "t2"), primaryLabel = "Escuchar",
            primaryAccessibilityLabel = "Reproducir álbum Album A",
        ),
        HomeHeroItem(
            key = "playlist:p1", kind = HomeHeroKind.PLAYLIST, sourceId = "p1",
            title = "Favorites", subtitle = "Playlist de tu colección", meta = "2 canciones",
            coverIds = listOf("cover"), fallbackMediaUri = null,
            trackIds = listOf("t1", "t2"), primaryLabel = "Abrir playlist",
        ),
    )

    @Test fun carouselActionsKeepTagsTargetsAndDoNotAutoPlayOnSwipe() {
        val primary = mutableListOf<String>()
        var all = 0
        var shuffle = 0
        compose.setContent {
            MichiTheme {
                AeroHomeHeroCarousel(
                    items = items,
                    onPrimary = { primary += it.sourceId },
                    onPlayAll = { all++ },
                    onShuffle = { shuffle++ },
                )
            }
        }

        compose.onNodeWithTag("home_hero_carousel").assertExists()
        compose.onNodeWithTag("m60_feature_play")
            .assertContentDescriptionEquals("Escuchar")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("quick_play_button")
            .assertContentDescriptionEquals("Reproducir todas las canciones")
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("shuffle_button").assertHeightIsAtLeast(48.dp)
        assertEquals(listOf("t1"), primary)
        assertEquals(0, all)
        assertEquals(0, shuffle)
        compose.onNodeWithTag("home_hero_page_indicator")
            .assertContentDescriptionEquals("Página 1 de 3")

        compose.onNodeWithTag("home_hero_carousel").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("home_hero_page_indicator")
            .assertContentDescriptionEquals("Página 2 de 3")
        compose.onNodeWithTag("m60_feature_play")
            .assertContentDescriptionEquals("Reproducir álbum Album A")
        assertEquals("Swipe must not start playback", listOf("t1"), primary)
        assertEquals(0, all)
        assertEquals(0, shuffle)
    }

    @Test fun selectedPageFollowsItsStableKeyWhenTheLibrarySnapshotChanges() {
        var currentItems by mutableStateOf(items)
        compose.setContent {
            MichiTheme {
                AeroHomeHeroCarousel(
                    items = currentItems,
                    onPrimary = {},
                    onPlayAll = {},
                    onShuffle = {},
                )
            }
        }
        compose.onNodeWithTag("home_hero_carousel").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("home_hero_page_indicator")
            .assertContentDescriptionEquals("Página 2 de 3")

        compose.runOnIdle {
            currentItems = listOf(items[2], items[0], items[1])
        }
        compose.waitForIdle()
        compose.onNodeWithTag("home_hero_page_indicator")
            .assertContentDescriptionEquals("Página 3 de 3")
    }
}
