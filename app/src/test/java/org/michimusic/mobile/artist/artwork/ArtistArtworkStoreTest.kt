package org.michimusic.mobile.artist.artwork

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.artist.ArtistArtworkAssetRecord
import org.michimusic.data.artist.ArtistArtworkLinkRecord
import org.michimusic.data.artist.ArtistArtworkSelectionRecord
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtistArtworkStoreTest {
    private lateinit var context: Context
    private lateinit var database: ArtistEnrichmentCacheDatabase
    private lateinit var store: RoomArtistArtworkStore
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java)
            .allowMainThreadQueries().build()
        store = RoomArtistArtworkStore(database, context, clock = { 5L })
    }

    @After fun tearDown() {
        database.close()
        File(context.filesDir, "artist-artwork").deleteRecursively()
    }

    @Test
    fun pruneKeepsReferencedArtworkAndDeletesOnlyOrphans() = runBlocking {
        val root = File(context.filesDir, "artist-artwork").apply { mkdirs() }
        val referenced = File(root, "referenced.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val orphan = File(root, "orphan.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val sha = "a".repeat(64)
        val assetId = "sha256:$sha"
        val dao = database.artistEnrichmentDao()
        dao.putArtworkAsset(ArtistArtworkAssetRecord(
            assetId = assetId, confirmedMbid = mbid, verifiedQid = "Q48284",
            source = "COMMONS_P18",
            sourcePageUrl = "https://commons.wikimedia.org/wiki/File:Midnight_Oil.jpg",
            imageUrl = "https://upload.wikimedia.org/x.jpg",
            localRelativePath = "artist-artwork/referenced.jpg", sha256 = sha,
            mimeType = "image/jpeg", width = 1000, height = 600,
            licenseId = "CC BY-SA 2.0",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
            attribution = "Author", evidence = "VERIFIED_QID_REFERENCE", downloadedAtMs = 1L,
        ))
        dao.putArtworkLink(ArtistArtworkLinkRecord(
            libraryId = "lib", creditKey = "midnight oil", albumGroupKey = "group",
            slot = "ARTIST_THUMB", confirmedMbid = mbid, assetId = assetId,
            verifiedQid = "Q48284", source = "COMMONS_P18",
            sourcePageUrl = "https://commons.wikimedia.org/wiki/File:Midnight_Oil.jpg",
            imageUrl = "https://upload.wikimedia.org/x.jpg",
            licenseId = "CC BY-SA 2.0",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
            attribution = "Author", evidence = "VERIFIED_QID_REFERENCE", linkedAtMs = 1L,
        ))
        dao.putArtworkSelection(ArtistArtworkSelectionRecord(
            libraryId = "lib", creditKey = "midnight oil", albumGroupKey = "group",
            slot = "ARTIST_THUMB", assetId = assetId, confirmedMbid = mbid,
            focusX = 0.5f, focusY = 0.5f, manuallyChosen = false,
            revision = 1L, updatedAtMs = 1L,
        ))
        val anchor = ArtistIdentityAnchor("lib", "midnight oil", "group", mbid, "Q48284", "Midnight Oil")

        val freed = store.pruneCache()

        assertTrue("Referenced artwork must survive pruning", referenced.isFile)
        assertFalse("Unreferenced artwork must be pruned", orphan.exists())
        assertEquals(3L, freed)
        val loaded = store.load(anchor).single()
        assertEquals(ArtworkLoadStatus.READY, loaded.status)
        assertEquals(referenced.absolutePath, loaded.localImagePath)
    }
}
