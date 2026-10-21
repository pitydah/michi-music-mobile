package org.michimusic.data.cache

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Migration3To4Test {

    private val TEST_DB = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        MichiDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration3To4_preservesPlaylistsAndCreatesDeviceLocalLibrary() {
        // Create DB at version 3 manually
        val dbV3 = helper.createDatabase(TEST_DB, 3).apply {
            execSQL("INSERT INTO cached_playlists (id, name, trackIds, trackCount) VALUES ('pl_1', 'Rock Clasico', 'track_a,track_b,track_c', 3)")
            execSQL("INSERT INTO cached_playlists (id, name, trackIds, trackCount) VALUES ('pl_empty', 'Vacia', '', 0)")
            execSQL("INSERT INTO app_settings (`key`, `value`) VALUES ('theme', 'dark')")
            execSQL("INSERT INTO play_history (trackId, playedAt) VALUES ('track_a', 123456789)")
            close()
        }

        // Run migration to version 4
        val dbV4 = helper.runMigrationsAndValidate(TEST_DB, 4, true, MichiDatabase.MIGRATION_3_4)

        // Verify libraries table contains default DEVICE_LOCAL
        val libCursor = dbV4.query("SELECT libraryId, name, type FROM libraries WHERE libraryId = '${MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID}'")
        assertTrue("Default DEVICE_LOCAL library must exist", libCursor.moveToFirst())
        assertEquals("Este teléfono", libCursor.getString(1))
        assertEquals("DEVICE_LOCAL", libCursor.getString(2))
        libCursor.close()

        // Verify playlist_headers migrated all playlists
        val headerCursor = dbV4.query("SELECT playlistId, name FROM playlist_headers ORDER BY playlistId ASC")
        assertEquals("Must migrate both playlists", 2, headerCursor.count)
        headerCursor.moveToFirst()
        assertEquals("pl_1", headerCursor.getString(0))
        assertEquals("Rock Clasico", headerCursor.getString(1))
        headerCursor.moveToNext()
        assertEquals("pl_empty", headerCursor.getString(0))
        assertEquals("Vacia", headerCursor.getString(1))
        headerCursor.close()

        // Verify playlist_entries migrated tracks in exact order
        val entriesCursor = dbV4.query("SELECT trackId, position FROM playlist_entries WHERE playlistId = 'pl_1' ORDER BY position ASC")
        assertEquals(3, entriesCursor.count)
        entriesCursor.moveToFirst()
        assertEquals("track_a", entriesCursor.getString(0))
        assertEquals(0, entriesCursor.getInt(1))
        entriesCursor.moveToNext()
        assertEquals("track_b", entriesCursor.getString(0))
        assertEquals(1, entriesCursor.getInt(1))
        entriesCursor.moveToNext()
        assertEquals("track_c", entriesCursor.getString(0))
        assertEquals(2, entriesCursor.getInt(1))
        entriesCursor.close()

        // Verify app_settings and play_history are untouched
        val settingsCursor = dbV4.query("SELECT `value` FROM app_settings WHERE `key` = 'theme'")
        assertTrue(settingsCursor.moveToFirst())
        assertEquals("dark", settingsCursor.getString(0))
        settingsCursor.close()

        val historyCursor = dbV4.query("SELECT trackId, playedAt FROM play_history WHERE trackId = 'track_a'")
        assertTrue(historyCursor.moveToFirst())
        assertEquals(123456789L, historyCursor.getLong(1))
        historyCursor.close()

        dbV4.close()
    }
}
