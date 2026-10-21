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
class Migration4To5Test {

    private val TEST_DB = "migration-test-4-5.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        MichiDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration4To5_createsTransferCheckpointsTableAndPreservesData() {
        // Create DB at version 4
        val dbV4 = helper.createDatabase(TEST_DB, 4).apply {
            execSQL("INSERT INTO app_settings (`key`, `value`) VALUES ('test_key', 'test_value')")
            close()
        }

        // Run migration to version 5
        val dbV5 = helper.runMigrationsAndValidate(TEST_DB, 5, true, MichiDatabase.MIGRATION_4_5)

        // Verify transfer_checkpoints table exists and allows insertion
        dbV5.execSQL("""
            INSERT INTO transfer_checkpoints (
                transferKey, transferId, remoteDeviceId, sourceLibraryId, destinationLibraryId,
                trackId, remoteTrackId, tempFilePath, finalFilePath, expectedSize,
                receivedBytes, verifiedBytes, expectedSha256, etag, transferState,
                createdAt, updatedAt
            ) VALUES (
                'dev1:lib1:trk1', 'tx_123', 'dev1', 'src_lib', 'lib1',
                'trk1', 'rem_trk1', '/tmp/part.tmp', '/storage/trk1.flac', 5000000,
                2500000, 2500000, 'sha256abc', 'etag123', 'IN_PROGRESS',
                1000, 2000
            )
        """.trimIndent())

        val cursor = dbV5.query("SELECT transferKey, transferId, receivedBytes, transferState FROM transfer_checkpoints WHERE transferKey = 'dev1:lib1:trk1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("dev1:lib1:trk1", cursor.getString(0))
        assertEquals("tx_123", cursor.getString(1))
        assertEquals(2500000L, cursor.getLong(2))
        assertEquals("IN_PROGRESS", cursor.getString(3))
        cursor.close()

        // Verify existing settings preserved
        val settingsCursor = dbV5.query("SELECT `value` FROM app_settings WHERE `key` = 'test_key'")
        assertTrue(settingsCursor.moveToFirst())
        assertEquals("test_value", settingsCursor.getString(0))
        settingsCursor.close()

        dbV5.close()
    }
}
