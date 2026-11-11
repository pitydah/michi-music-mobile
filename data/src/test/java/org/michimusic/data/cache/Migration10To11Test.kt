package org.michimusic.data.cache

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Requires generated Room schema v11 and Android/Robolectric execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Migration10To11Test {
    @get:Rule val helper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        MichiDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsGenreWithoutChangingOwnershipOrVerifiedCopyEvidence() {
        val name = "ui7-genre-migration-10-11.db"
        helper.createDatabase(name, 10).apply {
            execSQL(
                "INSERT INTO cached_tracks (id,title,artist,album,duration,size,format," +
                    "bitrate,sampleRate,channels,coverId,trackNumber,year,filepath," +
                    "downloaded,sourceId,verifiedSize,verifiedSha256,verifiedAt,verifiedAlgorithm,albumArtist) " +
                    "VALUES ('same','Old A','Artist','Album',1,100,'flac',0,44100,2,'',0,2025," +
                    "'/storage/a.flac',1,'peer-a',100,'${"a".repeat(64)}',123,'sha256','Band')"
            )
            execSQL(
                "INSERT INTO cached_tracks (id,title,artist,album,duration,size,format," +
                    "bitrate,sampleRate,channels,coverId,trackNumber,year,filepath," +
                    "downloaded,sourceId,verifiedSize,verifiedSha256,verifiedAt,verifiedAlgorithm,albumArtist) " +
                    "VALUES ('same','Old B','Other','Album',1,200,'flac',0,44100,2,'',0,2025," +
                    "'/storage/b.flac',0,'peer-b',0,'',0,'','Band')"
            )
            close()
        }
        val migrated = helper.runMigrationsAndValidate(name, 11, true, MichiDatabase.MIGRATION_10_11)
        migrated.query("SELECT sourceId,filepath,genre,verifiedSha256,verifiedSize " +
            "FROM cached_tracks WHERE id='same' ORDER BY sourceId").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("peer-a", c.getString(0))
            assertEquals("/storage/a.flac", c.getString(1))
            assertEquals("", c.getString(2))
            assertEquals("a".repeat(64), c.getString(3))
            assertEquals(100L, c.getLong(4))
            assertTrue(c.moveToNext())
            assertEquals("peer-b", c.getString(0))
            assertEquals("/storage/b.flac", c.getString(1))
            assertEquals("", c.getString(2))
            assertEquals("", c.getString(3))
            assertEquals(0L, c.getLong(4))
            assertEquals(false, c.moveToNext())
        }
        migrated.execSQL("UPDATE cached_tracks SET genre='Jazz' WHERE sourceId='peer-a' AND id='same'")
        migrated.query("SELECT genre FROM cached_tracks WHERE sourceId='peer-a' AND id='same'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Jazz", c.getString(0))
        }
    }
}
