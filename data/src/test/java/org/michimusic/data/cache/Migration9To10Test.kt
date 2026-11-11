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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Migration9To10Test {
    @get:Rule val helper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        MichiDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsVerificationMetadataWithoutTouchingCachedAudio() {
        val name = "migration-verification-9-10.db"
        helper.createDatabase(name, 9).apply {
            execSQL(
                "INSERT INTO cached_tracks " +
                    "(`id`,`title`,`artist`,`album`,`duration`,`size`,`format`,`bitrate`," +
                    "`sampleRate`,`channels`,`coverId`,`trackNumber`,`year`,`filepath`," +
                    "`downloaded`,`sourceId`) VALUES " +
                    "('server_1','Tema','Artista','Álbum',120000,44100,'flac',900,44100,2," +
                    "'cover',1,1999,'/data/synced/server_1.flac',1,'peer-a')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 10, true, MichiDatabase.MIGRATION_9_10)
        db.query(
            "SELECT title, filepath, downloaded, sourceId, verifiedSize, verifiedSha256, " +
                "verifiedAt, verifiedAlgorithm FROM cached_tracks WHERE id='server_1'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Tema", c.getString(0))
            assertEquals("/data/synced/server_1.flac", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertEquals("peer-a", c.getString(3))
            assertEquals(0L, c.getLong(4))
            assertEquals("", c.getString(5))
            assertEquals(0L, c.getLong(6))
            assertEquals("", c.getString(7))
        }

        db.execSQL(
            "UPDATE cached_tracks SET verifiedSize=44100, verifiedSha256='${"a".repeat(64)}', " +
                "verifiedAt=1234, verifiedAlgorithm='sha256' WHERE id='server_1'",
        )
        db.query("SELECT verifiedSize, verifiedSha256 FROM cached_tracks WHERE id='server_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(44100L, c.getLong(0))
            assertEquals("a".repeat(64), c.getString(1))
        }
    }
}
