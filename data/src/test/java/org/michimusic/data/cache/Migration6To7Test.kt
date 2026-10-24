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
class Migration6To7Test {

    private val TEST_DB = "migration-test-6-7.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        MichiDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration6To7_createsApprovedSyncPlansTableAndIndices() {
        // Create DB at version 6
        val dbV6 = helper.createDatabase(TEST_DB, 6).apply {
            execSQL("INSERT INTO saved_queue (`id`, `trackIds`, `startIndex`, `positionMs`, `repeatMode`, `shuffleMode`, `entryIds`, `locationIds`) VALUES (0, 'trk1', 0, 0, 0, 0, 'e1', 'loc1')")
            close()
        }

        // Run migration to version 7
        val dbV7 = helper.runMigrationsAndValidate(TEST_DB, 7, true, MichiDatabase.MIGRATION_6_7)

        // Insert an approved plan in version 7
        dbV7.execSQL("""
            INSERT INTO approved_sync_plans (
                `planId`, `endpointId`, `endpointName`, `scope`, `selectionJson`,
                `deliveryChoiceJson`, `itemsJson`, `manifestVersion`, `manifestChecksum`,
                `totalSelectedTracks`, `tracksToDownloadCount`, `totalDownloadBytes`,
                `status`, `executionState`, `createdAt`
            ) VALUES (
                'plan_test_001', 'srv_1', 'Desktop 1', 'ALBUMS', '{}',
                '{}', '[]', 1, 'sha_test_1',
                2, 2, 50000000,
                'READY', 'QUEUED', 1700000000000
            )
        """.trimIndent())

        val cursor = dbV7.query("SELECT planId, endpointId, scope, executionState, totalSelectedTracks FROM approved_sync_plans WHERE planId = 'plan_test_001'")
        assertTrue(cursor.moveToFirst())
        assertEquals("plan_test_001", cursor.getString(0))
        assertEquals("srv_1", cursor.getString(1))
        assertEquals("ALBUMS", cursor.getString(2))
        assertEquals("QUEUED", cursor.getString(3))
        assertEquals(2, cursor.getInt(4))
        cursor.close()
    }
}
