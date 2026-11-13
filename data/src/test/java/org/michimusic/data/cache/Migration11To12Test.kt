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
class Migration11To12Test {

    private val dbName = "migration-11-12-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MichiDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsTagEvidenceWithoutChangingTrackOwnershipOrCopies() {
        helper.createDatabase(dbName, 11).use { db ->
            db.execSQL(
                """
                INSERT INTO cached_tracks
                    (id, title, artist, album, albumArtist, genre, duration, size,
                     format, bitrate, sampleRate, channels, coverId, trackNumber,
                     year, filepath, downloaded, sourceId, verifiedSize,
                     verifiedSha256, verifiedAt, verifiedAlgorithm)
                VALUES
                    ('same', 'A', 'Artist A', 'Album A', 'Band A', 'Jazz', 1000, 123,
                     'flac', 0, 44100, 2, '', 1, 2020, '/storage/a.flac', 1,
                     'peer-a', 123, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                     123, 'sha256')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO cached_tracks
                    (id, title, artist, album, albumArtist, genre, duration, size,
                     format, bitrate, sampleRate, channels, coverId, trackNumber,
                     year, filepath, downloaded, sourceId, verifiedSize,
                     verifiedSha256, verifiedAt, verifiedAlgorithm)
                VALUES
                    ('same', 'B', 'Artist B', 'Album B', 'Band B', '', 2000, 456,
                     'flac', 0, 44100, 2, '', 2, 2021, '/storage/b.flac', 0,
                     'peer-b', 0, '', 0, '')
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            dbName,
            12,
            true,
            MichiDatabase.MIGRATION_11_12,
        ).use { db ->
            db.query(
                "SELECT sourceId, filepath, genre, verifiedSha256, verifiedSize " +
                    "FROM cached_tracks ORDER BY sourceId",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("peer-a", cursor.getString(0))
                assertEquals("/storage/a.flac", cursor.getString(1))
                assertEquals("Jazz", cursor.getString(2))
                assertEquals(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    cursor.getString(3),
                )
                assertEquals(123L, cursor.getLong(4))

                assertTrue(cursor.moveToNext())
                assertEquals("peer-b", cursor.getString(0))
                assertEquals("/storage/b.flac", cursor.getString(1))
                assertEquals("", cursor.getString(2))
                assertEquals("", cursor.getString(3))
                assertEquals(0L, cursor.getLong(4))
            }

            db.execSQL(
                """
                INSERT INTO embedded_tag_evidence
                    (sourceId, trackId, sizeBytes, modifiedAt, parserVersion,
                     genre, genreValues, title, artist, album, albumArtist,
                     composer, trackNumber, trackTotal, discNumber, discTotal, date)
                VALUES
                    ('peer-a', 'same', 123, 456, 3, 'Jazz', 'Jazz;Fusion',
                     'A', 'Artist A', 'Album A', 'Band A', 'Composer', 1, 10, 1, 2, '2024')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO embedded_tag_evidence
                    (sourceId, trackId, sizeBytes, modifiedAt, parserVersion,
                     genre, genreValues, title, artist, album, albumArtist,
                     composer, trackNumber, trackTotal, discNumber, discTotal, date)
                VALUES
                    ('peer-b', 'same', 456, 789, 3, '', '',
                     'B', 'Artist B', 'Album B', 'Band B', '', 2, 10, 1, 2, '2025')
                """.trimIndent(),
            )

            db.query(
                "SELECT sourceId, trackId, genre, genreValues, parserVersion " +
                    "FROM embedded_tag_evidence ORDER BY sourceId",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("peer-a", cursor.getString(0))
                assertEquals("same", cursor.getString(1))
                assertEquals("Jazz", cursor.getString(2))
                assertEquals("Jazz;Fusion", cursor.getString(3))
                assertEquals(3, cursor.getInt(4))

                assertTrue(cursor.moveToNext())
                assertEquals("peer-b", cursor.getString(0))
                assertEquals("same", cursor.getString(1))
                assertEquals("", cursor.getString(2))
                assertEquals("", cursor.getString(3))
                assertEquals(3, cursor.getInt(4))
                assertTrue(!cursor.moveToNext())
            }

            // The migration must preserve the existing playlist table, even
            // though tag evidence is the only new schema object in v12.
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='cached_playlists'",
            ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        }
    }
}
