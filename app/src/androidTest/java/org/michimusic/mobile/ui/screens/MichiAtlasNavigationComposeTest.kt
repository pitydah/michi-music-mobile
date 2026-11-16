package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.MichiTheme

/** Navigation and playback authority stay with the existing UI7 panel. */
@RunWith(AndroidJUnit4::class)
class MichiAtlasNavigationComposeTest {
    @get:Rule val rule = createComposeRule()

    private fun awaitAtlasPlayability() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("michi_atlas_playability_loading")
                .fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun documentedNewWaveMapOpensRelationAndKeepsAudioOwnership() {
        val fixture = genreLayoutFixture()
        var playCount = 0
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight()) {
                    GenreLibraryPanel(
                        genres = fixture.genres,
                        playableTrackIds = fixture.tracks.map { it.id }.toSet(),
                        currentTrack = null,
                        isPlaying = false,
                        onPlay = { _, _ -> playCount++ },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(mosaicTileTag("New Wave")).performClick()
        rule.onNodeWithTag("michi_atlas_open_new wave").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen").assertIsDisplayed()
        rule.onNodeWithTag("michi_atlas_genre_screen").performScrollToNode(
            hasTestTag("michi_atlas_connections_section"))
        rule.onNodeWithTag("michi_atlas_relation_synth-pop").assertExists().performClick()
        rule.onNodeWithTag("michi_atlas_relation_detail").assertIsDisplayed()
        rule.onNodeWithTag("michi_atlas_genre_screen").performScrollToNode(
            hasTestTag("michi_atlas_toolbar"))
        rule.onNodeWithContentDescription("Volver a New Wave").performClick()
        rule.onNodeWithTag("genre_detail_header").assertIsDisplayed()
        org.junit.Assert.assertEquals(0, playCount)
    }

    @Test fun connectedGenreTraversalReturnsToOriginWithoutForeignLibraryOrPlayback() {
        val fixture = genreLayoutFixture()
        var plays = 0
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight()) {
                    GenreLibraryPanel(
                        genres = fixture.genres,
                        playableTrackIds = fixture.tracks.map { it.id }.toSet(),
                        currentTrack = null,
                        isPlaying = false,
                        onPlay = { _, _ -> plays++ },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(mosaicTileTag("New Wave")).performClick()
        rule.onNodeWithTag("michi_atlas_open_new wave").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_connections_section"))
        rule.onNodeWithTag("michi_atlas_relation_synth-pop").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_explore_synth-pop"))
        rule.onNodeWithTag("michi_atlas_explore_synth-pop").performClick()
        rule.onNodeWithTag("michi_atlas_genre_title").assertIsDisplayed()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_library_section"))
        rule.onNodeWithTag("michi_atlas_external_library_notice").assertExists()
        rule.onNodeWithTag("michi_atlas_library_count").assertDoesNotExist()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_toolbar"))
        rule.onNodeWithContentDescription("Volver al Atlas anterior").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_library_section"))
        rule.onNodeWithTag("michi_atlas_library_count").assertExists()
        // Root back must return to the same original genre detail.
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_toolbar"))
        rule.onNodeWithContentDescription("Volver a New Wave").performClick()
        rule.onNodeWithTag("genre_detail_header").assertIsDisplayed()
        org.junit.Assert.assertEquals(0, plays)
    }


    @Test fun relatedGenreWithScopedSongsShowsItsOwnLibraryAndPlaysOnlyItsOwnTrack() {
        val nw = Track(id = "nw1", title = "New Wave One", artist = "Origin Band",
            genre = "New Wave", sourceId = "local", filepath = "content://local/nw1")
        val synth = Track(id = "sp1", title = "Synth One", artist = "Target Band",
            genre = "Synth-Pop", sourceId = "local", filepath = "content://local/sp1")
        val genres = groupLibraryGenres(listOf(nw, synth))
        val played = mutableListOf<String>()
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight()) {
                    GenreLibraryPanel(
                        genres = genres,
                        playableTrackIds = setOf("nw1", "sp1"),
                        currentTrack = null, isPlaying = false,
                        onPlay = { tracks, _ -> played += tracks.map { it.id } },
                    )
                }
            }
        }
        rule.onNodeWithTag(mosaicTileTag("New Wave")).performClick()
        rule.onNodeWithTag("michi_atlas_open_new wave").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_connections_section"))
        rule.onNodeWithTag("michi_atlas_relation_synth-pop").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_explore_synth-pop"))
        rule.onNodeWithTag("michi_atlas_explore_synth-pop").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_library_section"))
        rule.onNodeWithTag("michi_atlas_library_count").assertExists()
        rule.onNodeWithContentDescription("Reproducir música de Synth-Pop desde Atlas")
            .performClick()
        org.junit.Assert.assertEquals(listOf("sp1"), played)
    }

    @Test fun relatedGenreWithGloballyBlockedTrackDoesNotTriggerPlayback() {
        val nw = Track(id = "nw1", title = "Origin", genre = "New Wave")
        val synth = Track(id = "sp1", title = "Blocked", genre = "Synth-Pop")
        val genres = groupLibraryGenres(listOf(nw, synth))
        var plays = 0
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight()) {
                    GenreLibraryPanel(
                        genres = genres, playableTrackIds = setOf("nw1", "sp1"),
                        globallyBlockedTrackIds = setOf("sp1"),
                        currentTrack = null, isPlaying = false,
                        onPlay = { _, _ -> plays++ },
                    )
                }
            }
        }
        rule.onNodeWithTag(mosaicTileTag("New Wave")).performClick()
        rule.onNodeWithTag("michi_atlas_open_new wave").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_connections_section"))
        rule.onNodeWithTag("michi_atlas_relation_synth-pop").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_explore_synth-pop"))
        rule.onNodeWithTag("michi_atlas_explore_synth-pop").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_library_section"))
        rule.onNodeWithContentDescription("Reproducir música de Synth-Pop desde Atlas")
            .assertIsNotEnabled()
        org.junit.Assert.assertEquals(0, plays)
    }

    @Test fun unpublishedConnectedStyleIsExplainedWithoutPhantomNavigation() {
        val genre = LibraryGenre("krautrock", "Krautrock", emptyList(), 0)
        rule.setContent {
            MichiTheme {
                MichiAtlasGenreScreen(
                    genre = genre, viewMode = GenreViewMode.FEATURED,
                    poster = null, mosaic = null,
                    scopedGenres = listOf(genre), playableTrackIds = emptySet(),
                    globallyBlockedTrackIds = emptySet(),
                    editorialAssets = emptyMap(), mosaicAssets = emptyMap(),
                    scopeIdentity = "test", onBack = {}, onPlay = { _, _ -> },
                )
            }
        }
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_connections_section"))
        rule.onNodeWithTag("michi_atlas_relation_techno").performClick()
        rule.onNodeWithTag("michi_atlas_target_not_published").assertExists()
        rule.onNodeWithTag("michi_atlas_explore_techno").assertDoesNotExist()
    }

    @Test fun bannerAtlasReturnsToSameGenreWithoutReplacingThePlaybackButtons() {
        val fixture = genreLayoutFixture()
        var playCount = 0
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight()) {
                    GenreLibraryPanel(
                        genres = fixture.genres,
                        playableTrackIds = fixture.tracks.map { it.id }.toSet(),
                        currentTrack = null,
                        isPlaying = false,
                        onPlay = { _, _ -> playCount++ },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(mosaicTileTag("Pop")).performClick()
        rule.onNodeWithTag("michi_atlas_open_pop").assertIsDisplayed().performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen").assertIsDisplayed()
        rule.onNodeWithTag("michi_atlas_library_count").assertIsDisplayed()
        rule.onNodeWithContentDescription("Volver a Pop").performClick()
        rule.onNodeWithTag("genre_detail_header").assertIsDisplayed()
        rule.onNodeWithContentDescription("Reproducir género Pop").assertIsDisplayed()
        org.junit.Assert.assertEquals(0, playCount)
    }

    @Test fun atlasTrackRowsPlayOnlyTheAuthorizedScopedQueueAndIndex() {
        val blocked = Track(id = "blocked", title = "Blocked", artist = "A",
            genre = "Pop", sourceId = "local", filepath = "content://a")
        val first = Track(id = "first", title = "First", artist = "B",
            genre = "Pop", sourceId = "local", filepath = "content://b")
        val second = Track(id = "second", title = "Second", artist = "C",
            genre = "Pop", sourceId = "local", filepath = "content://c")
        val played = mutableListOf<Pair<List<String>, Int>>()
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight()) {
                    GenreLibraryPanel(
                        genres = groupLibraryGenres(listOf(blocked, first, second)),
                        playableTrackIds = setOf("blocked", "first", "second"),
                        globallyBlockedTrackIds = setOf("blocked"),
                        currentTrack = null, isPlaying = false,
                        onPlay = { queue, index -> played += queue.map { it.id } to index },
                    )
                }
            }
        }
        rule.onNodeWithTag(mosaicTileTag("Pop")).performClick()
        rule.onNodeWithTag("michi_atlas_open_pop").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_library_section"))
        awaitAtlasPlayability()
        rule.onNodeWithTag("michi_atlas_track_0").assertIsNotEnabled()
        rule.onNodeWithTag("michi_atlas_track_2").performScrollTo().performClick()
        org.junit.Assert.assertEquals(listOf(listOf("first", "second") to 1), played)
    }

    @Test fun atlasCannotPlayWhenCurrentSessionDisallowsLocalPlayback() {
        val pop = Track(id = "pop1", title = "Pop", genre = "Pop")
        var dispatched = 0
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight()) {
                    GenreLibraryPanel(
                        genres = groupLibraryGenres(listOf(pop)),
                        playableTrackIds = setOf("pop1"), currentTrack = null,
                        isPlaying = false, onPlay = { _, _ -> dispatched++ },
                        canPlayLocally = { false },
                    )
                }
            }
        }
        rule.onNodeWithTag(mosaicTileTag("Pop")).performClick()
        rule.onNodeWithTag("michi_atlas_open_pop").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_library_section"))
        awaitAtlasPlayability()
        rule.onNodeWithTag("michi_atlas_track_0").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Reproducir música de Pop desde Atlas").performScrollTo().performClick()
        org.junit.Assert.assertEquals(0, dispatched)
    }

    @Test fun exploredRelatedGenreCanOpenItsExactLibraryWithoutStartingPlayback() {
        val nw = Track(id = "nw1", title = "New Wave 1", genre = "New Wave")
        val synth = Track(id = "s1", title = "Synth 1", genre = "Synth-Pop")
        var dispatched = 0
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight()) {
                    GenreLibraryPanel(genres = groupLibraryGenres(listOf(nw, synth)),
                        playableTrackIds = setOf("nw1", "s1"),
                        currentTrack = null, isPlaying = false,
                        onPlay = { _, _ -> dispatched++ })
                }
            }
        }
        rule.onNodeWithTag(mosaicTileTag("New Wave")).performClick()
        rule.onNodeWithTag("michi_atlas_open_new wave").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_connections_section"))
        rule.onNodeWithTag("michi_atlas_relation_synth-pop").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_explore_synth-pop"))
        rule.onNodeWithTag("michi_atlas_explore_synth-pop").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_library_section"))
        rule.onNodeWithTag("michi_atlas_open_library_synth-pop").performScrollTo().performClick()
        rule.onNodeWithTag("genre_detail_title").assertTextEquals("Synth-Pop")
        rule.onNodeWithTag("michi_atlas_genre_screen").assertDoesNotExist()
        org.junit.Assert.assertEquals(0, dispatched)
    }


    @Test fun atlasBannerArtworkAndSectionJumpsNeverDispatchPlayback() {
        val fixture = genreLayoutFixture()
        var played = 0
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight()) {
                    GenreLibraryPanel(
                        genres = fixture.genres,
                        playableTrackIds = fixture.tracks.map { it.id }.toSet(),
                        currentTrack = null,
                        isPlaying = false,
                        onPlay = { _, _ -> played++ },
                    )
                }
            }
        }
        rule.onNodeWithTag(mosaicTileTag("New Wave")).performClick()
        rule.onNodeWithTag("genre_detail_header_artwork").performClick()
        rule.onNodeWithTag("michi_atlas_genre_screen").assertIsDisplayed()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_section_jumps"))
        rule.onNodeWithTag("michi_atlas_jump_history").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("michi_atlas_history_section").assertIsDisplayed()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_section_jumps"))
        rule.onNodeWithTag("michi_atlas_jump_library").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("michi_atlas_library_section").assertIsDisplayed()
        org.junit.Assert.assertEquals(0, played)
    }

    @Test fun playableOnlyFilterNeverMakesBlockedTrackClickable() {
        val tracks = (1..12).map { n ->
            Track(id = "t$n", title = "Song $n", artist = "Singer", genre = "Pop",
                sourceId = "local", filepath = "content://local/t$n")
        }
        val genres = groupLibraryGenres(tracks)
        var dispatched: List<String> = emptyList()
        rule.setContent {
            MichiTheme {
                Box(Modifier.width(411.dp).fillMaxHeight()) {
                    GenreLibraryPanel(
                        genres = genres,
                        playableTrackIds = setOf("t11", "t12"),
                        currentTrack = null, isPlaying = false,
                        onPlay = { queue, _ -> dispatched = queue.map { it.id } },
                    )
                }
            }
        }
        rule.onNodeWithTag(mosaicTileTag("Pop")).performClick()
        rule.onNodeWithTag("michi_atlas_open_pop").performClick()
        awaitAtlasPlayability()
        rule.onNodeWithTag("michi_atlas_genre_screen")
            .performScrollToNode(hasTestTag("michi_atlas_library_section"))
        rule.onNodeWithTag("michi_atlas_filter_playable").performScrollTo().performClick()
        rule.onNodeWithTag("michi_atlas_track_0").performScrollTo().performClick()
        org.junit.Assert.assertEquals(listOf("t11", "t12"), dispatched)
    }

}
