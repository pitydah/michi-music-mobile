package org.michimusic.data.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.LibraryEntity
import org.michimusic.data.cache.MichiDatabase

/**
 * The library cache and the enrichment cache are deliberately separate databases.
 * An enrichment failure or deletion must never touch track, album, playlist, queue or
 * playback rows, and the enrichment schema must never leak into the library cache.
 */
@RunWith(RobolectricTestRunner::class)
class ArtistEnrichmentCrossDatabaseTest {
    private lateinit var context: Context
    private val libraryName = "test_cross_library.db"
    private val enrichmentName = "test_cross_enrichment.db"
    private lateinit var library: MichiDatabase
    private lateinit var enrichment: ArtistEnrichmentCacheDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(libraryName)
        context.deleteDatabase(enrichmentName)
        library = Room.databaseBuilder(context, MichiDatabase::class.java, libraryName)
            .allowMainThreadQueries().build()
        enrichment = Room.databaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java, enrichmentName)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        library.close()
        enrichment.close()
        context.deleteDatabase(libraryName)
        context.deleteDatabase(enrichmentName)
    }

    private suspend fun seedLibrary() {
        library.libraryDao().insertLibrary(
            LibraryEntity(
                libraryId = "lib_device_local",
                name = "Biblioteca local",
                type = "DEVICE_LOCAL",
                isWritable = true,
            ),
        )
        library.trackDao().insert(
            CachedTrack(
                id = "track-1",
                title = "Diesel and Dust",
                artist = "Midnight Oil",
                album = "Diesel and Dust",
                duration = 220_000,
                sourceId = "",
            ),
        )
    }

    private suspend fun seedEnrichment() {
        val dao = enrichment.artistEnrichmentDao()
        dao.put(
            ArtistEnrichmentRecord(
                libraryId = "lib_device_local",
                creditKey = "midnight oil",
                albumGroupKey = "group-1",
                confirmedMbid = "05072650-4b05-4e8b-83d0-dba2576e306a",
                profileJson = "{}",
                savedAtMs = 1L,
                staleAfterMs = 2L,
                source = "musicbrainz-manual-selection",
                biographySourceUrl = null,
                portraitSourceUrl = null,
                portraitLicense = null,
                portraitLicenseUrl = null,
                portraitAttribution = null,
            ),
        )
    }

    @Test
    fun deletingTheEnrichmentDatabaseLeavesTheLibraryCacheIntact() = runBlocking {
        seedLibrary()
        seedEnrichment()
        assertEquals(1, library.trackDao().count())
        assertEquals("Midnight Oil", library.trackDao().getTracksById("track-1").singleOrNull()?.artist)

        enrichment.close()
        context.deleteDatabase(enrichmentName)

        library.close()
        library = Room.databaseBuilder(context, MichiDatabase::class.java, libraryName)
            .allowMainThreadQueries().build()
        assertEquals(1, library.trackDao().count())
        assertEquals("Diesel and Dust", library.trackDao().getTracksById("track-1").singleOrNull()?.title)
        assertTrue(library.libraryDao().observeAllLibraries().first().any {
            it.libraryId == "lib_device_local" && it.isWritable
        })
    }

    @Test
    fun theTwoDatabasesOwnDisjointTables() = runBlocking {
        seedLibrary()
        seedEnrichment()
        val libraryTables = tableNames(library)
        val enrichmentTables = tableNames(enrichment)
        assertTrue("cached_tracks" in libraryTables)
        assertTrue("libraries" in libraryTables)
        assertTrue("artist_artwork_selection" !in libraryTables)
        assertTrue("artist_enrichment" !in libraryTables)
        assertTrue("artist_artwork_selection" in enrichmentTables)
        assertTrue("artist_enrichment" in enrichmentTables)
        assertTrue("cached_tracks" !in enrichmentTables)
        assertTrue("libraries" !in enrichmentTables)
    }

    private fun tableNames(database: androidx.room.RoomDatabase): Set<String> =
        database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table'",
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
}
