package org.michimusic.mobile.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.ArtistEnrichmentSelectionStore
import org.michimusic.mobile.artist.ArtistEnrichmentService
import org.michimusic.mobile.artist.ArtistExternalProfile
import org.michimusic.mobile.artist.PersistedArtistProfile
import org.michimusic.mobile.artist.artwork.ArtistArtworkPersistence
import org.michimusic.mobile.artist.artwork.ArtistArtworkService
import org.michimusic.mobile.artist.artwork.artistIdentityAnchor
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.ui.components.ArtistMosaicEntry
import org.michimusic.mobile.ui.components.ArtistMosaicGrid
import java.io.File
import java.io.FileOutputStream

/**
 * Opt-in visual evidence for the real provider data. It renders the production
 * components with live verified profiles and waits for decoded bytes, then
 * writes PNGs to the app's internal files directory. Enable with
 * `-e michiLiveProviders true`; never part of CI.
 */
@RunWith(AndroidJUnit4::class)
class ArtistLiveScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val enabled =
        InstrumentationRegistry.getArguments().getString("michiLiveProviders") == "true"

    private suspend fun liveProfile(credit: String): ArtistExternalProfile {
        val repository = ArtistEnrichmentService.repository
        val candidate = repository.findCandidates(credit)
            .firstOrNull { it.name.equals(credit, ignoreCase = true) }
            ?: error("$credit must be found explicitly")
        return repository.confirmedProfile(candidate.mbid)
    }

    private fun liveStore(vararg profiles: Pair<String, ArtistExternalProfile>) =
        object : ArtistEnrichmentSelectionStore {
            private val byCredit = profiles.toMap()
            override suspend fun load(
                libraryId: String,
                credit: String,
                albumIds: List<String>,
            ): PersistedArtistProfile? = byCredit[credit]?.let { PersistedArtistProfile(it, false) }

            override suspend fun save(
                libraryId: String,
                credit: String,
                albumIds: List<String>,
                profile: ArtistExternalProfile,
            ) = Unit

            override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) = Unit
        }

    private fun midnightAnchor(profile: ArtistExternalProfile) = requireNotNull(
        artistIdentityAnchor(
            "live-qa-library",
            "Midnight Oil",
            listOf("live-qa-album-midnight-oil"),
            profile,
        ),
    )

    private fun flockAnchor(profile: ArtistExternalProfile) = requireNotNull(
        artistIdentityAnchor(
            "live-qa-library",
            "A Flock Of Seagulls",
            listOf("live-qa-album-flock"),
            profile,
        ),
    )

    private fun save(tag: String, fileName: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val directory = File(context.filesDir, "screenshots").apply { mkdirs() }
        val file = File(directory, fileName)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("LIVE_SCREENSHOT ${file.absolutePath} ${bitmap.width}x${bitmap.height}")
    }

    @Test
    fun captureMosaicWithVerifiedPortraitsAndALocalMonogram() {
        assumeTrue("live providers are opt-in", enabled)
        runBlocking {
            val midnight = liveProfile("Midnight Oil")
            val flock = liveProfile("A Flock Of Seagulls")
            // The production mosaic never paints a remote portrait: it paints the file
            // this device already saved. Prefetching the URL into Coil proved nothing,
            // which is why this capture used to wait twenty seconds for a photo that
            // could not appear.
            val service = ArtistArtworkService.from(context)
            service.resolve(midnightAnchor(midnight), forceRefresh = true)
            service.resolve(flockAnchor(flock), forceRefresh = true)
            val store = liveStore(
                "Midnight Oil" to midnight,
                "A Flock Of Seagulls" to flock,
            )
            val entries = listOf(
                ArtistMosaicEntry(
                    credit = "Midnight Oil",
                    albumIds = listOf("live-qa-album-midnight-oil"),
                    albumCount = 1,
                    trackCount = 8,
                ),
                ArtistMosaicEntry(
                    credit = "A Flock Of Seagulls",
                    albumIds = listOf("live-qa-album-flock"),
                    albumCount = 1,
                    trackCount = 6,
                ),
                ArtistMosaicEntry(
                    credit = "Artista local sin retrato",
                    albumIds = listOf("live-qa-album-local"),
                    albumCount = 1,
                    trackCount = 3,
                ),
            )
            compose.setContent {
                Box(
                    Modifier.width(412.dp).height(430.dp).background(Color(0xFF090B11))
                        .testTag("evidence_mosaic"),
                ) {
                    ArtistMosaicGrid(
                        artists = entries,
                        libraryId = "live-qa-library",
                        refreshToken = 0,
                        onOpenArtist = {},
                        selectionStore = store,
                        artworkStore = ArtistArtworkPersistence.from(context),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag(
                    "artist_mosaic_photo_loaded_Midnight Oil",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag(
                    "artist_mosaic_photo_loaded_A Flock Of Seagulls",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
            }
            save("evidence_mosaic", "artist-mosaic-after.png")
        }
    }

    @Test
    fun captureMidnightOilFichaWithVerifiedBiographyAndPortrait() {
        assumeTrue("live providers are opt-in", enabled)
        runBlocking {
            val midnight = liveProfile("Midnight Oil")
            val anchor = midnightAnchor(midnight)
            ArtistArtworkService.from(context).resolve(anchor, forceRefresh = true)
            val hero = ArtistArtworkPersistence.from(context).load(anchor)
                .firstOrNull {
                    it.slot == ArtworkSlot.ARTIST_HERO && it.status == ArtworkLoadStatus.READY
                }
            compose.setContent {
                Column(
                    Modifier.width(412.dp).background(Color(0xFF090B11)).padding(20.dp)
                        .testTag("evidence_ficha"),
                ) {
                    ArtistDetailHero(
                        artistCredit = "Midnight Oil",
                        portrait = null,
                        albumCount = 1,
                        trackCount = 8,
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        artwork = hero,
                    )
                    ArtistBiographySection(
                        artistCredit = "Midnight Oil",
                        profile = midnight,
                        isStale = false,
                        portrait = null,
                    )
                }
            }
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag(
                    "artist_detail_hero_photo_loaded",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
            }
            save("evidence_ficha", "artist-ficha-midnight-oil-after.png")
        }
    }
}
