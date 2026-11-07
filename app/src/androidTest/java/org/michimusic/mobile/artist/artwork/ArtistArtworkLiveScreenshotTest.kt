package org.michimusic.mobile.artist.artwork

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence
import org.michimusic.mobile.artist.ArtistEnrichmentSelectionStore
import org.michimusic.mobile.artist.ArtistEnrichmentService
import org.michimusic.mobile.artist.ArtistExternalProfile
import org.michimusic.mobile.artist.PersistedArtistProfile
import org.michimusic.mobile.artist.scopedArtistCreditKey
import org.michimusic.mobile.ui.components.ArtistMosaicEntry
import org.michimusic.mobile.ui.components.ArtistMosaicGrid
import org.michimusic.mobile.ui.screens.ArtistBiographySection
import org.michimusic.mobile.ui.screens.ArtistDetailHero
import org.michimusic.mobile.ui.theme.MichiTheme
import org.michimusic.mobile.ui.theme.PureWhite

@RunWith(AndroidJUnit4::class)
class ArtistArtworkLiveScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val libraryId = "r11-screenshot-library"
    private val midnightAlbums = listOf("r11-screenshot-midnight")
    private val flockAlbums = listOf("r11-screenshot-flock")
    private val midnight = "Midnight Oil"
    private val flock = "A Flock Of Seagulls"
    private val createdAnchors = mutableListOf<ArtistIdentityAnchor>()

    @After
    fun cleanup() {
        if (InstrumentationRegistry.getArguments().getString("michiLiveProviders") != "true") return
        runBlocking {
            val store = ArtistArtworkPersistence.from(context)
            createdAnchors.distinctBy { it.libraryId to it.creditKey }.forEach { store.clear(it) }
        }
    }

    @Test
    fun mosaicRendersThePersistedThumbCropWithoutNetworkOnScroll() {
        assumeTrue(
            "Live providers disabled",
            InstrumentationRegistry.getArguments()
                .getString("michiLiveProviders") == "true",
        )
        val profiles = runBlocking {
            val midnightProfile = profileFor(midnight)
            val flockProfile = profileFor(flock)
            val service = ArtistArtworkService.from(context)
            service.resolve(anchor(midnight, midnightProfile, midnightAlbums), forceRefresh = true)
            service.resolve(anchor(flock, flockProfile, flockAlbums), forceRefresh = true)
            mapOf(midnight to midnightProfile, flock to flockProfile)
        }
        val fakeStore = ProfileStore(libraryId, profiles)
        compose.setContent {
            MichiTheme {
                Box(
                    Modifier.width(412.dp).height(470.dp)
                        .background(androidx.compose.ui.graphics.Color(0xFF090B11))
                        .testTag("evidence_artwork_mosaic"),
                ) {
                    ArtistMosaicGrid(
                        artists = listOf(
                            ArtistMosaicEntry(midnight, midnightAlbums, 1, 8),
                            ArtistMosaicEntry(flock, flockAlbums, 1, 4),
                            ArtistMosaicEntry("Artista local sin retrato", listOf("r11-local"), 1, 2),
                        ),
                        libraryId = libraryId,
                        refreshToken = 0,
                        onOpenArtist = {},
                        selectionStore = fakeStore,
                        artworkStore = ArtistArtworkPersistence.from(context),
                    )
                }
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("artist_mosaic_photo_loaded_$flock", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        save("artist-artwork-mosaic-thumb-after.png")
    }

    @Test
    fun fichaRendersThePersistedHeroCropWithAttribution() {
        assumeTrue(
            "Live providers disabled",
            InstrumentationRegistry.getArguments()
                .getString("michiLiveProviders") == "true",
        )
        val profile = runBlocking {
            val p = profileFor(midnight)
            ArtistArtworkService.from(context).resolve(anchor(midnight, p, midnightAlbums), forceRefresh = true)
            p
        }
        val hero = runBlocking {
            ArtistArtworkPersistence.from(context)
                .load(anchor(midnight, profile, midnightAlbums))
                .firstOrNull { it.slot == ArtworkSlot.ARTIST_HERO && it.status == ArtworkLoadStatus.READY }
        }
        compose.setContent {
            MichiTheme {
                Column(
                    Modifier.width(412.dp)
                        .background(androidx.compose.ui.graphics.Color(0xFF090B11))
                        .padding(20.dp)
                        .testTag("evidence_artwork_ficha"),
                ) {
                    ArtistDetailHero(
                        artistCredit = midnight,
                        portrait = null,
                        albumCount = 1,
                        trackCount = 8,
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        artwork = hero,
                    )
                    ArtistBiographySection(
                        artistCredit = midnight,
                        profile = profile,
                        isStale = false,
                        portrait = null,
                        artwork = hero,
                    )
                }
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("artist_detail_hero_photo_loaded", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        save("artist-artwork-ficha-hero-after.png")
    }

    private suspend fun profileFor(credit: String): ArtistExternalProfile {
        val candidate = ArtistEnrichmentService.repository.findCandidates(credit)
            .firstOrNull { it.name.equals(credit, ignoreCase = true) }
            ?: error("No MusicBrainz candidate for $credit")
        return ArtistEnrichmentService.repository.confirmedProfile(candidate.mbid)
    }

    private fun anchor(
        credit: String,
        profile: ArtistExternalProfile,
        albumIds: List<String>,
    ): ArtistIdentityAnchor {
        val anchor = requireNotNull(
            artistIdentityAnchor(libraryId, credit, albumIds, profile),
        )
        createdAnchors += anchor
        return anchor
    }

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.filesDir, "screenshots").apply { mkdirs() }
        val file = File(directory, name)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        println("LIVE_ARTWORK_SCREENSHOT ${file.absolutePath} ${bitmap.width}x${bitmap.height} " +
            "${file.length()} bytes")
    }

    private class ProfileStore(
        private val libraryId: String,
        private val profiles: Map<String, ArtistExternalProfile>,
    ) : ArtistEnrichmentSelectionStore {
        override suspend fun load(
            libraryId: String,
            artistCredit: String,
            albumIds: List<String>,
        ): PersistedArtistProfile? {
            if (libraryId != this.libraryId) return null
            if (scopedArtistCreditKey(libraryId, artistCredit, albumIds) == null) return null
            return profiles[artistCredit]?.let { PersistedArtistProfile(it, stale = false) }
        }

        override suspend fun save(
            libraryId: String,
            artistCredit: String,
            albumIds: List<String>,
            profile: ArtistExternalProfile,
        ) = Unit

        override suspend fun clear(libraryId: String, artistCredit: String, albumIds: List<String>) = Unit
    }
}
