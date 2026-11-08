package org.michimusic.mobile.ui.coverflow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.library.coverflow.CoverFlowAlbum
import org.michimusic.core.models.Album
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum
import org.michimusic.mobile.ui.screens.albumCoverFlowPresentation
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w412dp-h915dp")
class MichiCoverFlowHostTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleAlbums = listOf(
        CoverFlowAlbum(id = "album_1", title = "Album One", artist = "Artist One", year = 2021, trackCount = 10),
        CoverFlowAlbum(id = "album_2", title = "Album Two", artist = "Artist Two", year = 2022, trackCount = 12),
        CoverFlowAlbum(id = "album_3", title = "Album Three", artist = "Artist Three", year = 2023, trackCount = 8),
    )

    @Test
    fun coverFlowHost_initialState_centersInitialAlbum() {
        var clickedIndex: Int? = null
        var selectedId: String? = null

        composeTestRule.setContent {
            MichiCoverFlowHost(
                albums = sampleAlbums,
                initialPage = 0,
                selectedAlbumId = "album_1",
                onAlbumClick = { clickedIndex = it },
                onAlbumSelected = { selectedId = it },
            )
        }

        composeTestRule.onNodeWithTag("coverflow_album_album_1")
            .assertIsDisplayed()
            .assertIsSelected()

        // Clicking centered album triggers onAlbumClick
        composeTestRule.onNodeWithTag("coverflow_album_album_1").performClick()
        assertEquals(0, clickedIndex)
    }

    @Test
    fun coverFlowHost_clickingSideAlbum_scrollsAndSelectsTarget() {
        var clickedIndex: Int? = null
        var currentSelectedId = "album_1"

        composeTestRule.setContent {
            var selected by androidx.compose.runtime.remember { mutableStateOf(currentSelectedId) }
            MichiCoverFlowHost(
                albums = sampleAlbums,
                initialPage = 0,
                selectedAlbumId = selected,
                onAlbumClick = { clickedIndex = it },
                onAlbumSelected = {
                    selected = it
                    currentSelectedId = it
                },
            )
        }

        // Tap album 2 which is adjacent
        composeTestRule.onNodeWithTag("coverflow_album_album_2").performClick()
        composeTestRule.waitForIdle()

        assertEquals("album_2", currentSelectedId)
        composeTestRule.onNodeWithTag("coverflow_album_album_2")
            .assertIsDisplayed()
            .assertIsSelected()
    }

    @Test
    fun scopedAlbumPresentation_selectsSideThenOpensSettledOpaqueIdentity() {
        val scopedAlbums = listOf(
            LocalAlbum(Album(id = "raw-1", title = "First", artist = "Artist"),
                listOf(Track(id = "track-1", title = "One"))),
            LocalAlbum(Album(id = "raw-2", title = "Second", artist = "Artist"),
                listOf(Track(id = "track-2", title = "Two"))),
        )
        val presentation = albumCoverFlowPresentation(scopedAlbums, LibraryScope.One("phone"))
        var selectedId by mutableStateOf(presentation.albums.first().id)
        var openedId: String? = null

        composeTestRule.setContent {
            MichiCoverFlowHost(
                albums = presentation.albums,
                selectedAlbumId = selectedId,
                onAlbumSelected = { selectedId = it },
                onAlbumClick = { index -> openedId = presentation.albums[index].id },
            )
        }

        composeTestRule.onNodeWithTag("coverflow_album_${presentation.albums[1].id}").performClick()
        composeTestRule.waitForIdle()
        assertEquals(presentation.albums[1].id, selectedId)
        assertEquals(null, openedId)

        composeTestRule.onNodeWithTag("coverflow_album_${presentation.albums[1].id}").performClick()
        composeTestRule.waitForIdle()
        assertEquals(presentation.albums[1].id, openedId)
    }

    @Test
    fun coverFlowHost_whenSelectedAlbumDeleted_recoversGracefullyWithoutCrash() {
        var currentSelectedId = "non_existent_deleted_album"

        composeTestRule.setContent {
            var selected by androidx.compose.runtime.remember { mutableStateOf(currentSelectedId) }
            MichiCoverFlowHost(
                albums = sampleAlbums,
                initialPage = 0,
                selectedAlbumId = selected,
                onAlbumSelected = {
                    selected = it
                    currentSelectedId = it
                },
            )
        }

        composeTestRule.waitForIdle()

        // Recovery contract: missing ID must be replaced by a valid album in the list
        assertEquals("album_1", currentSelectedId)
        composeTestRule.onNodeWithTag("coverflow_album_album_1")
            .assertIsDisplayed()
            .assertIsSelected()
    }

    @Test
    fun coverFlowHost_whenAlbumListShrinks_clampsToLastValidIndex() {
        var albumsState by mutableStateOf(sampleAlbums)
        var selectedId by mutableStateOf("album_3")

        composeTestRule.setContent {
            MichiCoverFlowHost(
                albums = albumsState,
                initialPage = 2,
                selectedAlbumId = selectedId,
                onAlbumSelected = { selectedId = it },
            )
        }

        composeTestRule.waitForIdle()
        assertEquals("album_3", selectedId)

        // Drop album_3 and album_2 so only album_1 remains
        albumsState = listOf(sampleAlbums[0])
        composeTestRule.waitForIdle()

        assertEquals("album_1", selectedId)
        composeTestRule.onNodeWithTag("coverflow_album_album_1")
            .assertIsDisplayed()
            .assertIsSelected()
    }
}
