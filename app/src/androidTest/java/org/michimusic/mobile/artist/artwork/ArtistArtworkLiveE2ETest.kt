package org.michimusic.mobile.artist.artwork

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence
import org.michimusic.mobile.artist.ArtistEnrichmentService
import org.michimusic.mobile.artist.canonicalArtistMbid

/**
 * Opt-in evidence against the real MusicBrainz, Wikidata, Wikimedia and Openverse
 * services. Never part of daily CI: it proves downloaded bytes, not merely URLs.
 */
@RunWith(AndroidJUnit4::class)
class ArtistArtworkLiveE2ETest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun federationDownloadsTwoCropsPersistsAndNeverCrossesArtists() {
        assumeTrue(
            "Live providers disabled",
            InstrumentationRegistry.getArguments()
                .getString("michiLiveProviders") == "true",
        )
        runBlocking {
            val midnight = profileFor("Midnight Oil")
            val flock = profileFor("A Flock Of Seagulls")
            val midnightAnchor = anchor("Midnight Oil", midnight)!!
            val flockAnchor = anchor("A Flock Of Seagulls", flock)!!
            val service = ArtistArtworkService.from(context)
            val store = ArtistArtworkPersistence.from(context)
            try {
                store.clear(midnightAnchor)
                store.clear(flockAnchor)
                val report = service.resolve(midnightAnchor, forceRefresh = true)
                println("LIVE_ARTWORK report=${report.status} outcomes=${report.outcomes}")
                assertTrue("Expected persisted artwork, got ${report.status}", report.selections.isNotEmpty())
                val thumb = report.selections[ArtworkSlot.ARTIST_THUMB]
                val hero = report.selections[ArtworkSlot.ARTIST_HERO]
                assertNotNull("Thumb selection missing", thumb)
                assertNotNull("Hero selection missing", hero)
                assertDecodable(thumb!!, midnightAnchor)
                assertDecodable(hero!!, midnightAnchor)
                assertEquals(midnightAnchor.confirmedMbid, thumb.confirmedMbid)
                assertEquals(midnightAnchor.confirmedMbid, hero.confirmedMbid)

                val cached = service.resolve(midnightAnchor)
                assertTrue("Second resolve must come from the private cache", cached.fromCache)
                assertEquals(report.selections.keys, cached.selections.keys)

                val flockReport = service.resolve(flockAnchor, forceRefresh = true)
                val flockThumb = flockReport.selections[ArtworkSlot.ARTIST_THUMB]
                assertNotNull("A Flock thumb missing", flockThumb)
                assertEquals(flockAnchor.confirmedMbid, flockThumb!!.confirmedMbid)
                assertDecodable(flockThumb, flockAnchor)
                assertNotEquals(
                    "Different artists must not share a persisted asset id",
                    thumb.assetId,
                    flockThumb.assetId,
                )

                val reopened = RoomArtistArtworkStore(
                    ArtistEnrichmentPersistence.database(context),
                    context,
                )
                val persisted = reopened.load(midnightAnchor)
                    .associateBy { it.slot }
                assertEquals(ArtworkLoadStatus.READY, persisted[ArtworkSlot.ARTIST_THUMB]?.status)
                assertEquals(ArtworkLoadStatus.READY, persisted[ArtworkSlot.ARTIST_HERO]?.status)
                println("LIVE_ARTWORK persisted=${persisted.keys} mbid=${midnightAnchor.confirmedMbid}")
            } finally {
                store.clear(midnightAnchor)
                store.clear(flockAnchor)
            }
        }
    }

    @Test
    fun openverseManualChoiceRequiresOriginLicenseAndPersistsDecodedBytes() {
        assumeTrue(
            "Live providers disabled",
            InstrumentationRegistry.getArguments()
                .getString("michiLiveProviders") == "true",
        )
        runBlocking {
            val midnight = profileFor("Midnight Oil")
            val anchor = anchor("Midnight Oil", midnight)!!
            val service = ArtistArtworkService.from(context)
            val store = ArtistArtworkPersistence.from(context)
            try {
                store.clear(anchor)
                val search = service.searchManual(anchor, "Midnight Oil")
                val candidates = when (search) {
                    is ProviderArtworkResult.Available -> search.items
                    else -> emptyList()
                }
                assertTrue("Openverse returned no manual candidates: $search", candidates.isNotEmpty())
                val flickr = candidates.firstOrNull {
                    it.source == ArtworkSource.OPENVERSE &&
                        it.canonicalSourcePage.startsWith("https://www.flickr.com/photos/")
                }
                assertNotNull("No Flickr-origin manual candidate", flickr)
                val chosen = service.chooseManual(anchor, flickr!!, ArtworkSlot.ARTIST_THUMB)
                assertNotNull("Openverse choice was rejected: ${chosen.message}", chosen.selections[ArtworkSlot.ARTIST_THUMB])
                val saved = chosen.selections.getValue(ArtworkSlot.ARTIST_THUMB)
                assertEquals(ArtworkSource.OPENVERSE, saved.source)
                assertTrue(saved.manuallyChosen)
                assertDecodable(saved, anchor)
                println("LIVE_ARTWORK manual source=${saved.source} license=${saved.licenseId} " +
                    "page=${saved.sourcePageUrl}")
            } finally {
                store.clear(anchor)
            }
        }
    }

    private suspend fun profileFor(credit: String) =
        ArtistEnrichmentService.repository.findCandidates(credit)
            .firstOrNull { it.name.equals(credit, ignoreCase = true) }
            ?.let { candidate ->
                val profile = ArtistEnrichmentService.repository.confirmedProfile(candidate.mbid)
                assertEquals(canonicalArtistMbid(candidate.mbid), profile.mbid)
                println("LIVE_ARTWORK profile credit=$credit mbid=${profile.mbid} qid=${profile.wikidataQid}")
                profile
            }
            ?: error("No MusicBrainz candidate for $credit")

    private fun anchor(credit: String, profile: org.michimusic.mobile.artist.ArtistExternalProfile): ArtistIdentityAnchor? =
        artistIdentityAnchor(
            libraryId = "r11-live-library",
            credit = credit,
            albumIds = listOf("r11-live-album-$credit"),
            profile = profile,
        )

    private fun assertDecodable(selection: ArtistArtworkSelection, anchor: ArtistIdentityAnchor) {
        assertEquals(anchor.confirmedMbid, selection.confirmedMbid)
        val path = selection.localImagePath
        assertNotNull("No local path for ${selection.slot}", path)
        val file = File(path!!)
        assertTrue("Artwork file missing: $path", file.isFile)
        assertTrue("Artwork file empty: $path", file.length() > 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        assertTrue("Artwork did not decode: $path", bounds.outWidth >= 320)
        assertTrue("Artwork did not decode: $path", bounds.outHeight >= 200)
        assertTrue(selection.licenseId.orEmpty().isNotBlank())
        assertTrue(selection.attribution.orEmpty().isNotBlank())
        assertTrue(selection.sourcePageUrl.orEmpty().startsWith("https://"))
        println("LIVE_ARTWORK ${selection.slot} asset=${selection.assetId} " +
            "bytes=${file.length()} ${bounds.outWidth}x${bounds.outHeight} " +
            "license=${selection.licenseId}")
    }
}
