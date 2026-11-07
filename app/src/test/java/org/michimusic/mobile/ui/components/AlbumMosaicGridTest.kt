package org.michimusic.mobile.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.Album
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum
import org.michimusic.mobile.ui.screens.albumNavigationIdentity
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w412dp-h915dp")
class AlbumMosaicGridTest {
    @get:Rule val rule = createComposeRule()
    private fun album(ids: List<String>) = LocalAlbum(
        Album(id = "same-mediastore-id", title = "Title", artist = "Artist"),
        ids.map { Track(id = it, title = "Track $it", artist = "Artist", album = "Title") },
    )

    @Test fun equalMediaStoreAlbumIdsAreStillSeparatelyNavigable() {
        val first = album(listOf("track-a"))
        val second = album(listOf("track-b"))
        val opened = mutableListOf<String>()
        rule.setContent { AlbumMosaicGrid(listOf(first, second), LibraryScope.All, onOpenAlbum = opened::add) }
        rule.onNodeWithTag("album_tile_${albumNavigationIdentity(first, LibraryScope.All)}").performClick()
        assertEquals(listOf(albumNavigationIdentity(first, LibraryScope.All)), opened)
        rule.onNodeWithTag("album_tile_${albumNavigationIdentity(second, LibraryScope.All)}").performClick()
        assertEquals(listOf(albumNavigationIdentity(first, LibraryScope.All), albumNavigationIdentity(second, LibraryScope.All)), opened)
    }

    @Test fun uniqueTokenCanOpenWithoutTriggeringPlayback() {
        val first = album(listOf("track-a"))
        var opened = ""
        rule.setContent { AlbumMosaicGrid(listOf(first), LibraryScope.All, onOpenAlbum = { opened = it }) }
        rule.onNodeWithTag("album_tile_${albumNavigationIdentity(first, LibraryScope.All)}")
            .assertIsDisplayed().performClick()
        assertEquals(albumNavigationIdentity(first, LibraryScope.All), opened)
    }
}
