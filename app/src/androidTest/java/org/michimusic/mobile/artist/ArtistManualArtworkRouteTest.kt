package org.michimusic.mobile.artist

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtworkEvidence
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkResolutionReport
import org.michimusic.mobile.artist.artwork.ArtworkRights
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource
import org.michimusic.mobile.ui.screens.ArtistDetailHero
import org.michimusic.mobile.ui.theme.MichiTheme

/**
 * The manual route is deterministic here: a pending Openverse-style candidate is
 * chosen, the production manual section updates its saved preview, and the same
 * selection is rendered by the production ficha hero. The live Openverse path
 * (origin licence + real bytes + Room persistence) is covered by the opt-in
 * ArtistArtworkLiveE2ETest.
 */
@RunWith(AndroidJUnit4::class)
class ArtistManualArtworkRouteTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val credit = "Midnight Oil"

    private fun profile() = ArtistExternalProfile(
        mbid = mbid,
        name = credit,
        biography = "Banda australiana de rock.",
        biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
        wikidataQid = "Q48284",
    )

    private fun localPng(): File {
        val directory = File(context.filesDir, "artist-artwork").apply { mkdirs() }
        val file = File(directory, "manual-route-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun selection(file: File, slot: ArtworkSlot) = ArtistArtworkSelection(
        slot = slot,
        assetId = "sha256:${"a".repeat(64)}",
        confirmedMbid = mbid,
        localImagePath = file.absolutePath,
        focus = ArtworkFocus(0.5f, 0.45f),
        manuallyChosen = true,
        revision = 7L,
        sourcePageUrl = "https://www.flickr.com/photos/1/51904192325",
        licenseId = "CC BY 2.0",
        licenseUrl = "https://creativecommons.org/licenses/by/2.0/",
        attribution = "Test Author",
        source = ArtworkSource.OPENVERSE,
        status = ArtworkLoadStatus.READY,
    )

    private fun candidate() = ArtistArtworkCandidate(
        source = ArtworkSource.OPENVERSE,
        stableAssetId = "openverse:manual-test",
        confirmedMbid = mbid,
        verifiedQid = "Q48284",
        canonicalSourcePage = "https://www.flickr.com/photos/1/51904192325",
        imageUrl = "https://live.staticflickr.com/1/51904192325_b.jpg",
        originalWidth = 1024,
        originalHeight = 768,
        mimeType = "image/jpeg",
        licenseId = "CC BY 2.0",
        licenseUrl = "https://creativecommons.org/licenses/by/2.0/",
        author = "Test Author",
        attribution = "Test Author",
        evidence = ArtworkEvidence.UNVERIFIED,
        rights = ArtworkRights.REQUIRES_REVIEW,
    )

    @Test
    fun choosingAManualCandidateShowsTheSavedCropAndTheFichaHero() {
        val file = localPng()
        var hero by mutableStateOf<ArtistArtworkSelection?>(null)
        compose.setContent {
            MichiTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ArtistArtworkManualSection(
                        profile = profile(),
                        artistCredit = credit,
                        libraryId = "lib-manual",
                        albumIds = listOf("album-diesel-and-dust"),
                        initialSelections = emptyMap(),
                        pendingCandidates = listOf(candidate()),
                        associationGroupKey = null,
                        onSelectionsChanged = { updated ->
                            hero = updated[ArtworkSlot.ARTIST_HERO]
                        },
                        chooser = { _, _, slot ->
                            ArtworkResolutionReport(
                                status = ArtworkLoadStatus.READY,
                                outcomes = emptyList(),
                                selections = mapOf(slot to selection(file, slot)),
                                fromCache = false,
                            )
                        },
                    )
                    hero?.let {
                        ArtistDetailHero(
                            artistCredit = credit,
                            portrait = null,
                            albumCount = 1,
                            trackCount = 1,
                            artwork = it,
                        )
                    }
                }
            }
        }

        compose.onNodeWithTag("artist_artwork_choose_ARTIST_HERO_0")
            .assertExists()
        compose.onNodeWithTag("artist_artwork_identity_confirm")
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag("artist_artwork_choose_ARTIST_HERO_0")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        compose.waitUntil(5_000) { hero != null }
        compose.onNodeWithText("Retrato guardado para ficha.").assertExists()
        compose.onNodeWithTag("artist_artwork_saved_ARTIST_HERO", useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithTag("artist_detail_hero_artwork", useUnmergedTree = true)
            .assertExists()
        assertNotNull(hero)
        assertEquals(ArtworkSlot.ARTIST_HERO, hero?.slot)
        file.delete()
    }
}
