package org.michimusic.mobile.artist.artwork

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtistArtworkRevisionTest {
    private lateinit var context: Context
    private lateinit var database: ArtistEnrichmentCacheDatabase
    private lateinit var store: RoomArtistArtworkStore
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java)
            .allowMainThreadQueries().build()
        // Every mutation uses the same wall-clock value: the revision must still move.
        store = RoomArtistArtworkStore(database, context, clock = { 5L })
    }

    @After fun tearDown() {
        database.close()
        File(context.filesDir, "artist-artwork").deleteRecursively()
    }

    private fun anchor(library: String = "lib-a", credit: String = "midnight oil") =
        ArtistIdentityAnchor(library, credit, "group", mbid, "Q48284", "Midnight Oil")

    private fun candidate(asset: String = "a") = ArtistArtworkCandidate(
        source = ArtworkSource.COMMONS_P18,
        stableAssetId = "commons:midnight-oil-$asset.jpg",
        confirmedMbid = mbid,
        verifiedQid = "Q48284",
        canonicalSourcePage = "https://commons.wikimedia.org/wiki/File:Midnight_Oil.jpg",
        imageUrl = "https://thumb.wikimedia.org/$asset.jpg",
        originalWidth = 1920,
        originalHeight = 1280,
        mimeType = "image/jpeg",
        licenseId = "CC BY-SA 2.0",
        licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
        author = "Author",
        attribution = "Author",
        evidence = ArtworkEvidence.VERIFIED_QID_REFERENCE,
        rights = ArtworkRights.VERIFIED_ALLOWED,
    )

    private fun downloaded(asset: String = "a") = DownloadedArtwork(
        relativePath = "artist-artwork/$asset.jpg",
        sha256 = asset.repeat(64).take(64),
        width = 1920,
        height = 1280,
        mimeType = "image/jpeg",
    )

    private suspend fun save(
        anchor: ArtistIdentityAnchor = anchor(),
        slot: ArtworkSlot = ArtworkSlot.ARTIST_THUMB,
        asset: String = "a",
    ) = store.save(anchor, candidate(asset), slot, downloaded(asset), ArtworkFocus(), false)

    @Test
    fun sameMillisecondReplacementAndFocusAdvanceTheMonotonicRevision() = runBlocking {
        val flow = store.observeLibraryRevision("lib-a")
        assertEquals(0L, withTimeout(5_000) { flow.first() })

        save(slot = ArtworkSlot.ARTIST_THUMB)
        assertEquals(1L, withTimeout(5_000) { flow.first { it >= 1L } })

        // Same slot, same clock value: a MAX(updatedAtMs) query would stay at 1.
        save(slot = ArtworkSlot.ARTIST_THUMB, asset = "b")
        assertEquals(2L, withTimeout(5_000) { flow.first { it >= 2L } })

        assertTrue(store.updateFocus(anchor(), ArtworkSlot.ARTIST_THUMB, ArtworkFocus(0.2f, 0.8f)))
        assertEquals(3L, withTimeout(5_000) { flow.first { it >= 3L } })
    }

    @Test
    fun deletingTheLastSelectionStillPublishesANewRevision() = runBlocking {
        val flow = store.observeLibraryRevision("lib-a")
        save()
        assertEquals(1L, withTimeout(5_000) { flow.first { it >= 1L } })

        store.clear(anchor())

        assertEquals(2L, withTimeout(5_000) { flow.first { it >= 2L } })
        assertTrue(store.load(anchor()).isEmpty())
    }

    @Test
    fun concurrentMutationsNeverLoseIncrements() = runBlocking {
        coroutineScope {
            (1..8).map { index ->
                async {
                    save(anchor = anchor(credit = "artist-$index"), asset = index.toString())
                }
            }.awaitAll()
        }
        assertEquals(8L, withTimeout(5_000) { store.observeLibraryRevision("lib-a").first { it >= 8L } })
    }

    @Test
    fun revisionsArePersistedAndIsolatedPerLibrary() = runBlocking {
        val name = "artist-revision-${System.nanoTime()}.db"
        val fileDatabase = Room.databaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val fileStore = RoomArtistArtworkStore(fileDatabase, context, clock = { 5L })
        try {
            fileStore.save(anchor("lib-a"), candidate("a"), ArtworkSlot.ARTIST_THUMB, downloaded("a"), ArtworkFocus(), false)
            fileStore.save(anchor("lib-a"), candidate("b"), ArtworkSlot.ARTIST_HERO, downloaded("b"), ArtworkFocus(), false)
            fileStore.save(anchor("lib-b"), candidate("c"), ArtworkSlot.ARTIST_THUMB, downloaded("c"), ArtworkFocus(), false)
        } finally {
            fileDatabase.close()
        }

        val reopened = Room.databaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val dao = reopened.artistEnrichmentDao()
            assertEquals(2L, withTimeout(5_000) { dao.observeLibraryArtworkRevision("lib-a").first() })
            assertEquals(1L, withTimeout(5_000) { dao.observeLibraryArtworkRevision("lib-b").first() })
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
