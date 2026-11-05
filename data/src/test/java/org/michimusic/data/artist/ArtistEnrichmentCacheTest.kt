package org.michimusic.data.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtistEnrichmentCacheTest {
    private lateinit var context: Context
    private val name = "test_artist_enrichment.db"
    private lateinit var database: ArtistEnrichmentCacheDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
        database = Room.databaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java, name)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    private fun record(library: String, group: String) = ArtistEnrichmentRecord(
        libraryId = library, creditKey = "patti austin & james ingram", albumGroupKey = group,
        confirmedMbid = "bd6d4f63-5cd0-4d78-a292-167997794c21",
        profileJson = "{}", savedAtMs = 100L, staleAfterMs = 200L,
        source = "manual-musicbrainz", biographySourceUrl = null,
        portraitSourceUrl = null, portraitLicense = null,
        portraitLicenseUrl = null, portraitAttribution = null,
    )

    @Test fun reopenRetainsOnlyTheScopedSelection() = runBlocking {
        database.artistEnrichmentDao().put(record("lib-a", "albums-1"))
        database.close()
        database = Room.databaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val dao = database.artistEnrichmentDao()
        assertNotNull(dao.get("lib-a", "patti austin & james ingram", "albums-1"))
        assertNull(dao.get("lib-b", "patti austin & james ingram", "albums-1"))
        assertNull(dao.get("lib-a", "patti austin & james ingram", "albums-2"))
    }

    @Test fun removingOneAssociationNeverDeletesAnotherLibrary() = runBlocking {
        val dao = database.artistEnrichmentDao()
        dao.put(record("lib-a", "albums-1"))
        dao.put(record("lib-b", "albums-1"))
        assertEquals(1, dao.delete("lib-a", "patti austin & james ingram", "albums-1"))
        assertEquals(0, dao.countForLibrary("lib-a"))
        assertEquals(1, dao.countForLibrary("lib-b"))
    }

    @Test fun artworkAssetAndBothSlotsSurviveReopen() = runBlocking {
        val dao = database.artistEnrichmentDao()
        val asset = ArtistArtworkAssetRecord(
            assetId = "sha256:abc", confirmedMbid = "bd6d4f63-5cd0-4d78-a292-167997794c21",
            verifiedQid = "Q1", source = "COMMONS_P18",
            sourcePageUrl = "https://commons.wikimedia.org/wiki/File:Midnight_Oil.jpg",
            imageUrl = "https://upload.wikimedia.org/x.jpg",
            localRelativePath = "artist-artwork/abc.jpg", sha256 = "abc",
            mimeType = "image/jpeg", width = 1200, height = 800,
            licenseId = "CC BY-SA 2.0", licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
            attribution = "Author", evidence = "VERIFIED_QID_REFERENCE", downloadedAtMs = 10L,
        )
        dao.putArtworkAsset(asset)
        listOf("ARTIST_THUMB", "ARTIST_HERO").forEachIndexed { index, slot ->
            dao.putArtworkSelection(ArtistArtworkSelectionRecord(
                libraryId = "lib-a", creditKey = "artist", albumGroupKey = "group", slot = slot,
                assetId = asset.assetId,
                confirmedMbid = asset.confirmedMbid, focusX = 0.5f, focusY = 0.5f,
                manuallyChosen = false, revision = index.toLong(), updatedAtMs = 11L,
            ))
        }
        database.close()
        database = Room.databaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val reopened = database.artistEnrichmentDao()
        val selections = reopened.getArtworkSelections(
            "lib-a", "artist", "group", asset.confirmedMbid,
        )
        assertEquals(2, selections.size)
        assertEquals(setOf("ARTIST_THUMB", "ARTIST_HERO"), selections.map { it.slot }.toSet())
        assertEquals(asset.localRelativePath, reopened.getArtworkAsset(asset.assetId)?.localRelativePath)
        assertEquals(1, reopened.countArtworkAssets())
    }

    @Test fun mbidChangeNeverResurrectsThePreviousArtistsArtwork() = runBlocking {
        val dao = database.artistEnrichmentDao()
        fun asset(id: String, mbid: String) = ArtistArtworkAssetRecord(
            assetId = id, confirmedMbid = mbid, verifiedQid = "Q1", source = "COMMONS_P18",
            sourcePageUrl = "https://commons.wikimedia.org/wiki/File:$id.jpg",
            imageUrl = "https://upload.wikimedia.org/$id.jpg",
            localRelativePath = "artist-artwork/$id.jpg", sha256 = id,
            mimeType = "image/jpeg", width = 1200, height = 800,
            licenseId = "CC BY-SA 2.0", licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
            attribution = "Author $id", evidence = "VERIFIED_QID_REFERENCE", downloadedAtMs = 10L,
        )
        fun link(id: String, mbid: String) = ArtistArtworkLinkRecord(
            libraryId = "lib-a", creditKey = "artist", albumGroupKey = "group",
            slot = "ARTIST_THUMB", confirmedMbid = mbid, assetId = id, verifiedQid = "Q1",
            source = "COMMONS_P18", sourcePageUrl = "https://commons.wikimedia.org/wiki/File:$id.jpg",
            imageUrl = "https://upload.wikimedia.org/$id.jpg", licenseId = "CC BY-SA 2.0",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/", attribution = "Author $id",
            evidence = "VERIFIED_QID_REFERENCE", linkedAtMs = 10L,
        )
        fun selection(id: String, mbid: String) = ArtistArtworkSelectionRecord(
            libraryId = "lib-a", creditKey = "artist", albumGroupKey = "group",
            slot = "ARTIST_THUMB", assetId = id, confirmedMbid = mbid,
            focusX = 0.5f, focusY = 0.5f, manuallyChosen = false, revision = 1L, updatedAtMs = 10L,
        )
        val oldMbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"
        val newMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        dao.persistArtwork(asset("old", oldMbid), link("old", oldMbid), selection("old", oldMbid))
        dao.persistArtwork(asset("new", newMbid), link("new", newMbid), selection("new", newMbid))

        assertEquals(0, dao.getArtworkSelections("lib-a", "artist", "group", oldMbid).size)
        assertEquals(1, dao.getArtworkSelections("lib-a", "artist", "group", newMbid).size)
        assertNull(dao.getArtworkLink("lib-a", "artist", "group", "ARTIST_THUMB", oldMbid))
        assertEquals("new", dao.getArtworkLink("lib-a", "artist", "group", "ARTIST_THUMB", newMbid)?.assetId)
    }
}
