package org.michimusic.mobile.artist

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtistPublicDiscographyUiTest {
    @get:Rule val compose = createComposeRule()
    private val mbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"

    @Test fun noRequestBeforeExplicitTapAndRemoteAlbumsAreNotPlayable() {
        var calls = 0
        val gateway = object : ArtistDiscographyGateway {
            override suspend fun fetchAlbumGroups(confirmedMbid: String, offset: Int): ArtistDiscographyPage {
                calls++
                assertEquals(mbid, confirmedMbid)
                assertEquals(0, offset)
                return ArtistDiscographyPage(listOf(ArtistPublicAlbum(mbid, "Diesel and Dust", 1987, "Album")), 0, 1, 1)
            }
        }
        compose.setContent { ArtistPublicDiscography(mbid, gateway = gateway) }
        compose.waitForIdle()
        assertEquals(0, calls)
        compose.onNodeWithTag("artist_public_discography_load").performClick()
        compose.waitUntil(5000) { calls == 1 }
        compose.onNodeWithText("Diesel and Dust").assertExists()
        compose.onNodeWithText("Catálogo externo", substring = true).assertExists()
    }
    @Test fun largeCatalogueDoesNotComposeOffScreenRows() {
        val catalogue = (0 until 500).map { idx ->
            val id = "00000000-0000-4000-8000-${idx.toString().padStart(12, '0')}"
            ArtistPublicAlbum(id, "Album $idx", 2000, "Album")
        }
        val gateway = object : ArtistDiscographyGateway {
            override suspend fun fetchAlbumGroups(confirmedMbid: String, offset: Int) =
                ArtistDiscographyPage(catalogue, offset, catalogue.size, catalogue.size)
        }
        compose.setContent { ArtistPublicDiscography(mbid, gateway = gateway) }
        compose.onNodeWithTag("artist_public_discography_load").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("artist_public_album_${catalogue.first().releaseGroupMbid}",
                useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("artist_public_album_${catalogue.last().releaseGroupMbid}",
            useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun invalidMbidCannotInitiateExternalLookup() {
        var calls = 0
        val gateway = object : ArtistDiscographyGateway {
            override suspend fun fetchAlbumGroups(confirmedMbid: String, offset: Int): ArtistDiscographyPage {
                calls++
                error("unreachable")
            }
        }
        compose.setContent { ArtistPublicDiscography("not-an-mbid", gateway = gateway) }
        compose.waitForIdle()
        compose.onNodeWithTag("artist_public_discography_load").assertDoesNotExist()
        assertEquals(0, calls)
    }
}
