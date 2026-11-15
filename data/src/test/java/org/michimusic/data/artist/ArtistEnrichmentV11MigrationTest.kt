package org.michimusic.data.artist

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A real schema-v10 fixture, upgraded by Room's registered 10->11 migration. */
@RunWith(RobolectricTestRunner::class)
class ArtistEnrichmentV11MigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), ArtistEnrichmentCacheDatabase::class.java,
    )

    @Test fun addingJournalKeepsArtistIdentityAndCreatesAllRequiredIndices() {
        val dbName = "artist-auto-v10-to-v11-${System.nanoTime()}.db"
        val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        helper.createDatabase(dbName, 10).apply {
            execSQL("""INSERT INTO artist_enrichment
                (libraryId, creditKey, albumGroupKey, confirmedMbid, profileJson,
                 savedAtMs, staleAfterMs, source)
                VALUES('lib', 'artist', 'identity', '$mbid', '{}', 1, 1000,
                 'musicbrainz-manual-selection')""".trimIndent())
            close()
        }
        val migrated = helper.runMigrationsAndValidate(
            dbName, 11, true, ArtistEnrichmentCacheDatabase.MIGRATION_10_11,
        )
        try {
            migrated.query("SELECT confirmedMbid FROM artist_enrichment WHERE libraryId='lib'").use {
                assertTrue(it.moveToFirst())
                assertEquals(mbid, it.getString(0))
            }
            migrated.query("PRAGMA table_info('artist_artwork_catalog_baseline')").use {
                assertEquals(4, it.count)
            }
            migrated.query("PRAGMA table_info('artist_artwork_auto_outbox')").use { cursor ->
                assertEquals(7, cursor.count)
                val keys = mutableListOf<Pair<Int, String>>()
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                val pkColumn = cursor.getColumnIndexOrThrow("pk")
                while (cursor.moveToNext()) {
                    val ordinal = cursor.getInt(pkColumn)
                    if (ordinal != 0) keys += ordinal to cursor.getString(nameColumn)
                }
                assertEquals(listOf("libraryId", "creditKey", "associationId", "albumKey", "sourceId"),
                    keys.sortedBy { it.first }.map { it.second })
            }
            migrated.query("PRAGMA index_list('artist_artwork_auto_outbox')").use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(1)
                assertTrue(names.contains("index_artist_artwork_auto_outbox_libraryId_creditKey_associationId_state"))
                assertTrue(names.contains("index_artist_artwork_auto_outbox_state_updatedAtMs"))
                assertTrue(names.contains("index_artist_artwork_auto_outbox_libraryId_sourceId_state"))
            }
        } finally {
            migrated.close()
        }
    }
}
