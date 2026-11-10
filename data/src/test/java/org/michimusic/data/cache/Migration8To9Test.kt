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
class Migration8To9Test {
    @get:Rule val helper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        MichiDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrationPreservesPlaylistAndAddsOnlyExclusionMetadata() {
        val name = "migration-exclusion-8-9.db"
        helper.createDatabase(name, 8).apply {
            execSQL("INSERT INTO cached_playlists (`id`,`name`,`trackIds`,`trackCount`,`sourceId`) " +
                "VALUES ('p','Mis discos','local_1',1,'')")
            execSQL(
                "INSERT INTO cached_tracks " +
                    "(`id`,`title`,`artist`,`album`,`duration`,`size`,`format`,`bitrate`," +
                    "`sampleRate`,`channels`,`coverId`,`trackNumber`,`year`,`filepath`," +
                    "`downloaded`,`sourceId`) VALUES " +
                    "('local_1','Tema','Artista','Álbum',120000,44100,'mp3',320,44100,2," +
                    "'cover',1,1999,'/Music/tema.mp3',0,'')",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 9, true, MichiDatabase.MIGRATION_8_9)
        db.query("SELECT name, trackIds FROM cached_playlists WHERE id='p'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Mis discos", c.getString(0))
            assertEquals("local_1", c.getString(1))
        }
        db.query("SELECT title, filepath FROM cached_tracks WHERE id='local_1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Tema", c.getString(0))
            assertEquals("/Music/tema.mp3", c.getString(1))
        }
        db.execSQL("INSERT INTO library_exclusions " +
            "(sourceId,trackId,guardFingerprint,title,artist,album,reason,createdAt,schemaVersion) " +
            "VALUES ('media','local_1','guard','Tema','Artista','Álbum','USER_HIDDEN',1,1)")
        db.query("SELECT COUNT(*) FROM library_exclusions").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
        }
        // Removing a visibility row is not removing a stored playlist, cached audio or file.
    }
}
