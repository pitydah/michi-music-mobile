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
}
