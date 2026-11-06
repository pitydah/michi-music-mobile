package org.michimusic.data.artist

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** v1 -> v2 preserves previously selected artists; no destructive migration is permitted. */
@RunWith(RobolectricTestRunner::class)
class ArtistEnrichmentMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ArtistEnrichmentCacheDatabase::class.java,
    )
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
            .addMigrations(ArtistEnrichmentCacheDatabase.MIGRATION_1_2,
                ArtistEnrichmentCacheDatabase.MIGRATION_2_3,
                ArtistEnrichmentCacheDatabase.MIGRATION_3_4,
                ArtistEnrichmentCacheDatabase.MIGRATION_4_5,
                ArtistEnrichmentCacheDatabase.MIGRATION_5_6,
                ArtistEnrichmentCacheDatabase.MIGRATION_6_7)
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

    @Test fun addingArtworkTablesPreservesExistingArtistSelection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "artist-migration-2-3-${System.nanoTime()}.db"
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
                CREATE TABLE IF NOT EXISTS `artist_track_evidence` (
                    `libraryId` TEXT NOT NULL, `trackId` TEXT NOT NULL,
                    `field` TEXT NOT NULL, `ordinal` INTEGER NOT NULL,
                    `value` TEXT NOT NULL, `observedAtMs` INTEGER NOT NULL,
                    `source` TEXT NOT NULL,
                    PRIMARY KEY(`libraryId`, `trackId`, `field`, `ordinal`)
                )
            """.trimIndent())
            legacy.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_track_evidence_libraryId_trackId` ON `artist_track_evidence` (`libraryId`, `trackId`)")
            legacy.execSQL("""
                INSERT INTO `artist_enrichment`
                    (`libraryId`,`creditKey`,`albumGroupKey`,`confirmedMbid`,`profileJson`,
                     `savedAtMs`,`staleAfterMs`,`source`)
                VALUES('lib-local','artist','group','bd6d4f63-5cd0-4d78-a292-167997794c21','{}',1,2,'manual')
            """.trimIndent())
            legacy.version = 2
        } finally { legacy.close() }
        val upgraded = Room.databaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java, name)
            .addMigrations(ArtistEnrichmentCacheDatabase.MIGRATION_1_2,
                ArtistEnrichmentCacheDatabase.MIGRATION_2_3,
                ArtistEnrichmentCacheDatabase.MIGRATION_3_4,
                ArtistEnrichmentCacheDatabase.MIGRATION_4_5,
                ArtistEnrichmentCacheDatabase.MIGRATION_5_6,
                ArtistEnrichmentCacheDatabase.MIGRATION_6_7)
            .build()
        try {
            assertEquals("bd6d4f63-5cd0-4d78-a292-167997794c21",
                upgraded.artistEnrichmentDao().get("lib-local", "artist", "group")?.confirmedMbid)
            assertEquals(0, upgraded.artistEnrichmentDao().countArtworkSelections())
            assertEquals(0, upgraded.artistEnrichmentDao().countArtworkAssets())
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun v4SelectionsGainMonotonicRevisionWithoutLosingArtwork() = runBlocking {
        val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val name = "artist-migration-4-5-${System.nanoTime()}.db"
        helper.createDatabase(name, 4).apply {
            execSQL("""
                INSERT INTO `artist_artwork_asset`
                    (`assetId`,`confirmedMbid`,`verifiedQid`,`source`,`sourcePageUrl`,
                     `imageUrl`,`localRelativePath`,`sha256`,`mimeType`,`width`,`height`,
                     `licenseId`,`licenseUrl`,`attribution`,`evidence`,`downloadedAtMs`)
                VALUES('sha256:abc','$mbid','Q48284','COMMONS_P18',
                       'https://commons.wikimedia.org/wiki/File:Midnight_Oil.jpg',
                       'https://thumb.wikimedia.org/Midnight_Oil.jpg',
                       'artist-artwork/abc.jpg','abc','image/jpeg',1920,1280,
                       'CC BY-SA 2.0','https://creativecommons.org/licenses/by-sa/2.0/',
                       'Fab_Photo_29','VERIFIED_QID_REFERENCE',1)
            """.trimIndent())
            execSQL("""
                INSERT INTO `artist_artwork_link`
                    (`libraryId`,`creditKey`,`albumGroupKey`,`slot`,`confirmedMbid`,
                     `assetId`,`verifiedQid`,`source`,`sourcePageUrl`,`imageUrl`,
                     `licenseId`,`licenseUrl`,`attribution`,`evidence`,`linkedAtMs`)
                VALUES('lib-a','artist','group','ARTIST_HERO','$mbid','sha256:abc',
                       'Q48284','COMMONS_P18',
                       'https://commons.wikimedia.org/wiki/File:Midnight_Oil.jpg',
                       'https://thumb.wikimedia.org/Midnight_Oil.jpg',
                       'CC BY-SA 2.0','https://creativecommons.org/licenses/by-sa/2.0/',
                       'Fab_Photo_29','VERIFIED_QID_REFERENCE',9)
            """.trimIndent())
            execSQL("""
                INSERT INTO `artist_artwork_selection`
                    (`libraryId`,`creditKey`,`albumGroupKey`,`slot`,`assetId`,
                     `confirmedMbid`,`focusX`,`focusY`,`manuallyChosen`,`revision`,`updatedAtMs`)
                VALUES('lib-a','artist','group','ARTIST_HERO','sha256:abc',
                       '$mbid',0.5,0.45,1,7,9)
            """.trimIndent())
            close()
        }
        val upgraded = helper.runMigrationsAndValidate(
            name, 5, true, ArtistEnrichmentCacheDatabase.MIGRATION_4_5,
        )
        try {
            upgraded.query(
                "SELECT revision, updatedAtMs FROM artist_artwork_revision WHERE libraryId=?",
                arrayOf("lib-a"),
            ).use { revision ->
                assertTrue(revision.moveToFirst())
                assertEquals(1L, revision.getLong(0))
                assertEquals(9L, revision.getLong(1))
            }
            upgraded.query(
                "SELECT slot, manuallyChosen, focusY FROM artist_artwork_selection " +
                    "WHERE libraryId=? AND confirmedMbid=?",
                arrayOf("lib-a", mbid),
            ).use { selection ->
                assertTrue(selection.moveToFirst())
                assertEquals("ARTIST_HERO", selection.getString(0))
                assertEquals(1, selection.getInt(1))
                assertEquals(0.45f, selection.getFloat(2), 0.0001f)
            }
            upgraded.query(
                "SELECT attribution, licenseId FROM artist_artwork_link " +
                    "WHERE libraryId=? AND confirmedMbid=? AND slot=?",
                arrayOf("lib-a", mbid, "ARTIST_HERO"),
            ).use { link ->
                assertTrue(link.moveToFirst())
                assertEquals("Fab_Photo_29", link.getString(0))
                assertEquals("CC BY-SA 2.0", link.getString(1))
            }
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun v5RowsGainAlbumEvidenceColumnWithoutLosingTheProfile() = runBlocking {
        val mbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"
        val name = "artist-migration-5-6-${System.nanoTime()}.db"
        helper.createDatabase(name, 5).apply {
            execSQL("""
                INSERT INTO `artist_enrichment`
                    (`libraryId`,`creditKey`,`albumGroupKey`,`confirmedMbid`,`profileJson`,
                     `savedAtMs`,`staleAfterMs`,`source`)
                VALUES('lib-a','artist','group','$mbid','{}',1,2,'manual')
            """.trimIndent())
            close()
        }
        val upgraded = helper.runMigrationsAndValidate(
            name, 6, true, ArtistEnrichmentCacheDatabase.MIGRATION_5_6,
        )
        try {
            upgraded.query(
                "SELECT confirmedMbid, albumIdsJson FROM artist_enrichment " +
                    "WHERE libraryId=? AND creditKey=? AND albumGroupKey=?",
                arrayOf("lib-a", "artist", "group"),
            ).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(mbid, row.getString(0))
                assertTrue(row.isNull(1))
            }
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun v6RowsGainMembershipFingerprintAndNormalizedRevisions() = runBlocking {
        val mbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"
        val name = "artist-migration-6-7-${System.nanoTime()}.db"
        helper.createDatabase(name, 6).apply {
            execSQL("""
                INSERT INTO `artist_enrichment`
                    (`libraryId`,`creditKey`,`albumGroupKey`,`confirmedMbid`,`profileJson`,
                     `savedAtMs`,`staleAfterMs`,`source`,`albumIdsJson`)
                VALUES('lib-a','artist','group','$mbid','{}',1,2,'manual','["album-1"]')
            """.trimIndent())
            execSQL("""
                INSERT INTO `artist_artwork_selection`
                    (`libraryId`,`creditKey`,`albumGroupKey`,`slot`,`assetId`,
                     `confirmedMbid`,`focusX`,`focusY`,`manuallyChosen`,`revision`,`updatedAtMs`)
                VALUES('lib-a','artist','group','ARTIST_THUMB','sha256:a',
                       '$mbid',0.4,0.6,1,9999999999999,9)
            """.trimIndent())
            execSQL("""
                INSERT INTO `artist_artwork_selection`
                    (`libraryId`,`creditKey`,`albumGroupKey`,`slot`,`assetId`,
                     `confirmedMbid`,`focusX`,`focusY`,`manuallyChosen`,`revision`,`updatedAtMs`)
                VALUES('lib-a','artist','group','ARTIST_HERO','sha256:b',
                       '$mbid',0.5,0.45,0,2,9)
            """.trimIndent())
            execSQL("INSERT INTO `artist_artwork_revision` VALUES('lib-a',9999999999999,9)")
            close()
        }
        val upgraded = helper.runMigrationsAndValidate(
            name, 7, true, ArtistEnrichmentCacheDatabase.MIGRATION_6_7,
        )
        try {
            upgraded.query(
                "SELECT membershipFingerprint FROM artist_enrichment " +
                    "WHERE libraryId=? AND creditKey=? AND albumGroupKey=?",
                arrayOf("lib-a", "artist", "group"),
            ).use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("group", row.getString(0))
            }
            upgraded.query(
                "SELECT revision, focusX, focusY, manuallyChosen FROM artist_artwork_selection " +
                    "WHERE libraryId=? ORDER BY rowid",
                arrayOf("lib-a"),
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(1L, rows.getLong(0))
                assertEquals(0.4f, rows.getFloat(1), 0.0001f)
                assertEquals(0.6f, rows.getFloat(2), 0.0001f)
                assertEquals(1, rows.getInt(3))
                assertTrue(rows.moveToNext())
                assertEquals(2L, rows.getLong(0))
            }
            upgraded.query(
                "SELECT revision FROM artist_artwork_revision WHERE libraryId=?",
                arrayOf("lib-a"),
            ).use { revision ->
                assertTrue(revision.moveToFirst())
                assertEquals(2L, revision.getLong(0))
            }
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun v3SelectionsGainMbidScopedLinksWithoutLosingProvenance() = runBlocking {
        val mbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val name = "artist-migration-3-4-${System.nanoTime()}.db"
        helper.createDatabase(name, 3).apply {
            execSQL("""
                INSERT INTO `artist_artwork_asset`
                    (`assetId`,`confirmedMbid`,`verifiedQid`,`source`,`sourcePageUrl`,
                     `imageUrl`,`localRelativePath`,`sha256`,`mimeType`,`width`,`height`,
                     `licenseId`,`licenseUrl`,`attribution`,`evidence`,`downloadedAtMs`)
                VALUES('sha256:abc','$mbid','Q48284','COMMONS_P18',
                       'https://commons.wikimedia.org/wiki/File:Midnight_Oil.jpg',
                       'https://thumb.wikimedia.org/Midnight_Oil.jpg',
                       'artist-artwork/abc.jpg','abc','image/jpeg',1920,1280,
                       'CC BY-SA 2.0','https://creativecommons.org/licenses/by-sa/2.0/',
                       'Fab_Photo_29','VERIFIED_QID_REFERENCE',1)
            """.trimIndent())
            execSQL("""
                INSERT INTO `artist_artwork_selection`
                    (`libraryId`,`creditKey`,`albumGroupKey`,`slot`,`assetId`,
                     `confirmedMbid`,`focusX`,`focusY`,`manuallyChosen`,`revision`,`updatedAtMs`)
                VALUES('lib-a','artist','group','ARTIST_THUMB','sha256:abc',
                       '$mbid',0.5,0.5,0,7,9)
            """.trimIndent())
            close()
        }
        val upgraded = helper.runMigrationsAndValidate(
            name, 4, true, ArtistEnrichmentCacheDatabase.MIGRATION_3_4,
        )
        try {
            upgraded.query(
                "SELECT assetId, licenseId, attribution FROM artist_artwork_link " +
                    "WHERE libraryId=? AND creditKey=? AND albumGroupKey=? AND slot=? AND confirmedMbid=?",
                arrayOf("lib-a", "artist", "group", "ARTIST_THUMB", mbid),
            ).use { link ->
                assertTrue(link.moveToFirst())
                assertEquals("sha256:abc", link.getString(0))
                assertEquals("CC BY-SA 2.0", link.getString(1))
                assertEquals("Fab_Photo_29", link.getString(2))
            }
            fun selectionCount(selectedMbid: String): Int = upgraded.query(
                "SELECT COUNT(*) FROM artist_artwork_selection " +
                    "WHERE libraryId=? AND creditKey=? AND albumGroupKey=? AND confirmedMbid=?",
                arrayOf("lib-a", "artist", "group", selectedMbid),
            ).use { count ->
                assertTrue(count.moveToFirst())
                count.getInt(0)
            }
            assertEquals(1, selectionCount(mbid))
            assertEquals(0, selectionCount(otherMbid))
        } finally {
            upgraded.close()
        }
    }
}
