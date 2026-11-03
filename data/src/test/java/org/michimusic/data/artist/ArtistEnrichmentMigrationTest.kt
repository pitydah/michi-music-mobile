package org.michimusic.data.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** v1 -> v2 preserves previously selected artists; no destructive migration is permitted. */
@RunWith(RobolectricTestRunner::class)
class ArtistEnrichmentMigrationTest {
    @Test fun oldSelectedArtistSurvivesAddingPerTrackEvidenceTable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "artist-migration-${System.nanoTime()}.db"
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            legacy.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_enrichment` (
                    `libraryId` TEXT NOT NULL, `creditKey` TEXT NOT NULL,
                    `albumGroupKey` TEXT NOT NULL, `confirmedMbid` TEXT NOT NULL,
                    `profileJson` TEXT NOT NULL, `savedAtMs` INTEGER NOT NULL,
                    `staleAfterMs` INTEGER NOT NULL, `source` TEXT NOT NULL,
                    `biographySourceUrl` TEXT, `portraitSourceUrl` TEXT,
                    `portraitLicense` TEXT, `portraitLicenseUrl` TEXT,
                    `portraitAttribution` TEXT,
                    PRIMARY KEY(`libraryId`, `creditKey`, `albumGroupKey`)
                )
            """.trimIndent())
            legacy.execSQL("""
                INSERT INTO `artist_enrichment`
                    (`libraryId`,`creditKey`,`albumGroupKey`,`confirmedMbid`,`profileJson`,
                     `savedAtMs`,`staleAfterMs`,`source`)
                VALUES('lib-local','artist','group','bd6d4f63-5cd0-4d78-a292-167997794c21','{}',1,2,'manual')
            """.trimIndent())
            legacy.version = 1
        } finally { legacy.close() }
        val upgraded = Room.databaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java, name)
            .addMigrations(ArtistEnrichmentCacheDatabase.MIGRATION_1_2)
            .build()
        try {
            assertEquals("bd6d4f63-5cd0-4d78-a292-167997794c21",
                upgraded.artistEnrichmentDao().get("lib-local", "artist", "group")?.confirmedMbid)
            assertTrue(upgraded.artistEnrichmentDao().getTrackEvidence("lib-local", "local_1").isEmpty())
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
