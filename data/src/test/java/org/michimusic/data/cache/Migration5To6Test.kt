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
class Migration5To6Test {

    private val TEST_DB = "migration-test-5-6.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        MichiDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration5To6_addsEntryIdsAndLocationIdsToSavedQueue() {
        // Create DB at version 5
        val dbV5 = helper.createDatabase(TEST_DB, 5).apply {
            execSQL("INSERT INTO saved_queue (`id`, `trackIds`, `startIndex`, `positionMs`, `repeatMode`, `shuffleMode`) VALUES (0, 'trk1,trk2', 1, 45000, 1, 0)")
            close()
        }

        // Run migration to version 6
        val dbV6 = helper.runMigrationsAndValidate(TEST_DB, 6, true, MichiDatabase.MIGRATION_5_6)

        // Verify columns exist and data preserved with default empty strings
        val cursor = dbV6.query("SELECT id, trackIds, startIndex, positionMs, entryIds, locationIds FROM saved_queue WHERE id = 0")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        assertEquals("trk1,trk2", cursor.getString(1))
        assertEquals(1, cursor.getInt(2))
        assertEquals(45000L, cursor.getLong(3))
        assertEquals("", cursor.getString(4))
        assertEquals("", cursor.getString(5))
        cursor.close()
    }
}
