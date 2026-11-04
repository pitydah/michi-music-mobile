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

/**
 * K16-01 / K16-02: version 8 moves the track and playlist cache from a single
 * `id` primary key to `(sourceId, id)`. Existing rows predate the concept of a
 * source, so the migration must keep every one of them under the local owner
 * (empty sourceId) and the rebuilt tables must accept the same ID twice when the
 * rows belong to different owners.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Migration7To8Test {

    private val TEST_DB = "migration-test-7-8.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        MichiDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration7To8_keepsExistingRowsUnderTheLocalSource() {
        val dbV7 = helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                "INSERT INTO cached_tracks (`id`, `title`, `artist`, `album`, `duration`, `size`, " +
                    "`format`, `bitrate`, `sampleRate`, `channels`, `coverId`, `trackNumber`, `year`, " +
                    "`filepath`, `downloaded`) VALUES " +
                    "('t1', 'Tema uno', 'Artista', 'Album', 1000, 2048, 'flac', 900, 44100, 2, '7', 1, 1999, '/music/t1.flac', 1)"
            )
            execSQL("INSERT INTO cached_playlists (`id`, `name`, `trackIds`, `trackCount`) VALUES ('p1', 'Lista', 't1', 1)")
            close()
        }

        val dbV8 = helper.runMigrationsAndValidate(TEST_DB, 8, true, MichiDatabase.MIGRATION_7_8)

        val tracks = dbV8.query("SELECT id, title, filepath, downloaded, sourceId FROM cached_tracks")
        assertTrue(tracks.moveToFirst())
        assertEquals("t1", tracks.getString(0))
        assertEquals("Tema uno", tracks.getString(1))
        assertEquals("/music/t1.flac", tracks.getString(2))
        assertEquals(1, tracks.getInt(3))
        assertEquals(LOCAL_SOURCE_ID, tracks.getString(4))
        assertEquals(1, tracks.count)
        tracks.close()

        val playlists = dbV8.query("SELECT id, name, trackCount, sourceId FROM cached_playlists")
        assertTrue(playlists.moveToFirst())
        assertEquals("p1", playlists.getString(0))
        assertEquals("Lista", playlists.getString(1))
        assertEquals(1, playlists.getInt(2))
        assertEquals(LOCAL_SOURCE_ID, playlists.getString(3))
        playlists.close()
    }

    @Test
    fun migration7To8_letsTwoLibrariesOwnTheSameId() {
        helper.createDatabase(TEST_DB, 7).close()
        val dbV8 = helper.runMigrationsAndValidate(TEST_DB, 8, true, MichiDatabase.MIGRATION_7_8)

        dbV8.execSQL(
            "INSERT INTO cached_tracks (`id`, `title`, `artist`, `album`, `duration`, `size`, " +
                "`format`, `bitrate`, `sampleRate`, `channels`, `coverId`, `trackNumber`, `year`, " +
                "`filepath`, `downloaded`, `sourceId`) VALUES " +
                "('shared', 'Local', '', '', 0, 0, '', 0, 0, 0, '', 0, 0, '', 0, '')"
        )
        dbV8.execSQL(
            "INSERT INTO cached_tracks (`id`, `title`, `artist`, `album`, `duration`, `size`, " +
                "`format`, `bitrate`, `sampleRate`, `channels`, `coverId`, `trackNumber`, `year`, " +
                "`filepath`, `downloaded`, `sourceId`) VALUES " +
                "('shared', 'Remoto', '', '', 0, 0, '', 0, 0, 0, '', 0, 0, '', 0, 'srv_1')"
        )
        dbV8.execSQL("INSERT INTO cached_playlists (`id`, `name`, `trackIds`, `trackCount`, `sourceId`) VALUES ('mix', 'Local', '', 0, '')")
        dbV8.execSQL("INSERT INTO cached_playlists (`id`, `name`, `trackIds`, `trackCount`, `sourceId`) VALUES ('mix', 'Remota', '', 0, 'srv_1')")

        val tracks = dbV8.query("SELECT title FROM cached_tracks WHERE id = 'shared' ORDER BY sourceId")
        assertEquals(2, tracks.count)
        assertTrue(tracks.moveToFirst())
        assertEquals("Local", tracks.getString(0))
        assertTrue(tracks.moveToNext())
        assertEquals("Remoto", tracks.getString(0))
        tracks.close()

        val playlists = dbV8.query("SELECT name FROM cached_playlists WHERE id = 'mix' ORDER BY sourceId")
        assertEquals(2, playlists.count)
        playlists.close()
    }
}
