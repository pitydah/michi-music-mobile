package org.michimusic.mobile.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.VisualPreferences

/** UI7 image-free Compose navigation/semantics gate. Android execution is required. */
@RunWith(AndroidJUnit4::class)
class GenreLibraryPanelComposeTest {
    @get:Rule val rule = createComposeRule()

    private fun tracks(): List<Track> = listOf(
        Track(id = "j1", title = "Jazz One", artist = "Artist A", album = "Album A",
            sourceId = "local", filepath = "content://music/j1", genre = "Jazz"),
        Track(id = "r1", title = "Rock One", artist = "Artist B", album = "Album B",
            sourceId = "local", filepath = "content://music/r1", genre = "Rock"),
        Track(id = "f1", title = "Folk One", artist = "Artist C", album = "Album C",
            sourceId = "local", filepath = "content://music/f1", genre = "Folk"),
    )

    @Test fun mosaicAndEditorialNavigateTheSameRealGenresWithoutImages() {
        val genres = groupLibraryGenres(tracks())
        rule.setContent {
            MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true)) {
                GenreLibraryPanel(genres, genres.flatMap { it.tracks }.map { it.id }.toSet(),
                    currentTrack = null, isPlaying = false, onPlay = { _, _ -> })
            }
        }
        rule.onNodeWithTag("genres_featured").assertExists()
        rule.onNodeWithTag("genre_tile_folk").assertContentDescriptionEquals("Abrir género Folk, 1 pista")
        rule.onNodeWithTag("genre_view_selector").performClick()
        rule.onNodeWithTag("genre_view_editorial").performClick()
        rule.onNodeWithTag("genres_editorial").assertExists()
        rule.onNodeWithTag("genre_editorial_folk").assertExists().performClick()
        rule.onNodeWithTag("genre_detail").assertExists()
        rule.onNodeWithTag("genre_detail_title").assertExists()
        rule.onNodeWithTag("genre_track_f1").assertExists()
    }

    @Test fun genreSearchOnlyFiltersCurrentCollection() {
        val genres = groupLibraryGenres(
            tracks() + listOf("Blues", "Ambient", "Clásica", "Soul", "Metal", "Pop", "Reggae")
                .mapIndexed { index, genre -> Track(id = "x$index", title = "X$index", genre = genre) },
        )
        rule.setContent {
            MichiTheme {
                GenreLibraryPanel(genres, genres.flatMap { it.tracks }.map { it.id }.toSet(),
                    currentTrack = null, isPlaying = false, onPlay = { _, _ -> })
            }
        }
        rule.onNodeWithTag("genres_search_input").performTextInput("JAZZ")
        rule.onNodeWithTag("genre_tile_jazz").assertExists()
        rule.onNodeWithTag("genre_tile_rock").assertDoesNotExist()
        rule.onNodeWithTag("genres_search_clear").performClick()
        rule.onNodeWithTag("genres_featured").assertExists()
    }

    @Test fun detailPlaysOnlyAuthorizedTracksWithRealIndex() {
        val songs = listOf(
            Track(id = "a", title = "A", genre = "Jazz", sourceId = "one", filepath = "a.flac"),
            Track(id = "b", title = "B", genre = "Jazz", sourceId = "one", filepath = "b.flac"),
        )
        var lastIds: List<String> = emptyList()
        var lastIndex = -1
        rule.setContent {
            MichiTheme {
                GenreLibraryPanel(groupLibraryGenres(songs), setOf("b"), currentTrack = null,
                    isPlaying = false, onPlay = { queue, index ->
                        lastIds = queue.map { it.id }; lastIndex = index
                    })
            }
        }
        rule.onNodeWithTag("genre_tile_jazz").performClick()
        rule.onNodeWithTag("genre_track_a").assertIsNotEnabled()
        rule.onNodeWithTag("genre_track_b").performClick()
        rule.runOnIdle { assertEquals(listOf("b"), lastIds); assertEquals(0, lastIndex) }
    }

    @Test fun duplicateOwnerIdsAreNotAccidentallyAuthorized() {
        val songs = listOf(
            Track(id = "dup", title = "One", genre = "Jazz", sourceId = "one", filepath = "one.flac"),
            Track(id = "dup", title = "Two", genre = "Jazz", sourceId = "two", filepath = "two.flac"),
            Track(id = "unique", title = "Unique", genre = "Jazz", sourceId = "one", filepath = "ok.flac"),
        )
        var played: List<String> = emptyList()
        rule.setContent {
            MichiTheme {
                GenreLibraryPanel(groupLibraryGenres(songs), setOf("dup", "unique"), null,
                    isPlaying = false, onPlay = { queue, _ -> played = queue.map { it.id } })
            }
        }
        rule.onNodeWithTag("genre_tile_jazz").performClick()
        rule.onNodeWithTag("genre_track_unique").performClick()
        rule.runOnIdle { assertEquals(listOf("unique"), played) }
    }

    @Test fun responsiveModeKeepsUsableGenreTileBoundsWithoutAssets() {
        data class Scenario(val width: Int, val fontScale: Float)
        var scenario by mutableStateOf(Scenario(320, 1f))
        val genres = groupLibraryGenres(tracks())
        rule.setContent {
            val config = Configuration().apply {
                screenWidthDp = scenario.width
                fontScale = scenario.fontScale
            }
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalDensity provides Density(density = 2f, fontScale = scenario.fontScale),
            ) {
                MichiTheme(visualPreferences = VisualPreferences(reducedMotion = true,
                    highContrast = true)) {
                    Box(Modifier.width(scenario.width.dp)) {
                        GenreLibraryPanel(genres, setOf("j1", "r1", "f1"), null,
                            isPlaying = false, onPlay = { _, _ -> })
                    }
                }
            }
        }
        for (s in listOf(Scenario(320, 1f), Scenario(360, 1f),
            Scenario(390, 1f), Scenario(411, 1.3f), Scenario(480, 2f))) {
            rule.runOnIdle { scenario = s }
            rule.waitForIdle()
            rule.onNodeWithTag("genres_featured").assertExists()
            val hero = rule.onNodeWithTag("genre_tile_folk").fetchSemanticsNode().boundsInRoot
            assertTrue("Genre tile tappable at $s", hero.height >= 48 * 2f)
        }
    }

    @Test fun blockedOwnerIdNeverReachesPlaybackEvenWhenGenreHasSingleTrack() {
        val only = Track(id = "shared", title = "Jazz", genre = "Jazz",
            sourceId = "peer-a", filepath = "one.flac")
        var invoked = false
        rule.setContent {
            MichiTheme {
                GenreLibraryPanel(groupLibraryGenres(listOf(only)), setOf("shared"), null,
                    isPlaying = false, onPlay = { _, _ -> invoked = true },
                    globallyBlockedTrackIds = setOf("shared"))
            }
        }
        rule.onNodeWithTag("genre_tile_jazz").performClick()
        rule.onNodeWithTag("genre_track_shared").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(false, invoked) }
    }
}
