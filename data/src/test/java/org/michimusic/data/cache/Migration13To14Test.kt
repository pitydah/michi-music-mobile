package org.michimusic.data.cache

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Migration13To14Test {
    private val dbName = "migration-13-14-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MichiDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsRetainedRevisionClockAndExactAppliedTransaction() {
        helper.createDatabase(dbName, 13).use { db ->
            db.execSQL(
                """
                INSERT INTO cached_tracks
                    (id,title,artist,album,duration,size,format,albumArtist,bitrate,
                     sampleRate,channels,coverId,trackNumber,year,filepath,downloaded,
                     sourceId,verifiedSize,verifiedSha256,verifiedAt,verifiedAlgorithm,genre)
                VALUES
                    ('track','Original','','',1000,123,'flac','',0,44100,2,'',0,0,
                     'content://media/1',1,'lib_device_local',123,
                     'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                     1,'sha256','Rock')
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            dbName,
            14,
            true,
            MichiDatabase.MIGRATION_13_14,
        ).use { db ->
            db.query(
                "SELECT metadataRevision FROM cached_tracks " +
                    "WHERE sourceId='lib_device_local' AND id='track'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
            db.query(
                "SELECT revision FROM metadata_revision_clock " +
                    "WHERE sourceId='lib_device_local' AND trackId='track'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }

            db.execSQL(
                "DELETE FROM cached_tracks WHERE sourceId='lib_device_local' AND id='track'",
            )
            db.execSQL(
                """
                INSERT INTO cached_tracks
                    (id,title,artist,album,duration,size,format,albumArtist,bitrate,
                     sampleRate,channels,coverId,trackNumber,year,filepath,downloaded,
                     sourceId,verifiedSize,verifiedSha256,verifiedAt,verifiedAlgorithm,
                     genre,metadataRevision)
                VALUES
                    ('track','Original','','',1000,123,'flac','',0,44100,2,'',0,0,
                     'content://media/1',1,'lib_device_local',0,'',0,'','Rock',0)
                """.trimIndent(),
            )
            db.query(
                "SELECT metadataRevision FROM cached_tracks " +
                    "WHERE sourceId='lib_device_local' AND id='track'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3L, cursor.getLong(0))
            }

            db.execSQL(
                """
                INSERT INTO metadata_applied_transaction
                    (transactionId,sourceId,trackId,uri,originalRevision,
                     committedRevision,candidateSha256,candidateBytes,appliedAt)
                VALUES
                    ('tx','lib_device_local','track','content://media/1',1,3,
                     'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                     456,99)
                """.trimIndent(),
            )
            db.execSQL(
                "DELETE FROM cached_tracks WHERE sourceId='lib_device_local' AND id='track'",
            )
            db.query(
                "SELECT committedRevision FROM metadata_applied_transaction " +
                    "WHERE transactionId='tx'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3L, cursor.getLong(0))
            }
        }
    }
}
