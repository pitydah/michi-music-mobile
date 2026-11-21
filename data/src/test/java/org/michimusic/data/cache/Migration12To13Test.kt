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

/**
 * ME-11R: v13 adds the people-evidence sidecar without touching cached identity.
 *
 * The table is keyed by the same composite track identity as the cache and is
 * declared with ON DELETE CASCADE so a peer cache eviction can never leave
 * orphan evidence behind. The migration must keep v12 evidence readable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Migration12To13Test {

    private val dbName = "migration-12-13-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MichiDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsPeopleEvidenceKeyedByCompositeTrackAndCascadesWithTheCache() {
        helper.createDatabase(dbName, 12).use { db ->
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
            // v12 sidecar must survive the upgrade untouched.
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
        }

        helper.runMigrationsAndValidate(
            dbName,
            13,
            true,
            MichiDatabase.MIGRATION_12_13,
        ).use { db ->
            // Composite identity: the same track id exists in two peer caches.
            db.execSQL(
                """
                INSERT INTO metadata_people_evidence
                    (sourceId, trackId, sizeBytes, modifiedAt, sourceSha256,
                     artistCount, artistValues, albumArtistCount, albumArtistValues,
                     receiptVersion)
                VALUES
                    ('peer-a', 'same', 123, 456,
                     'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                     2, 'Artist A;Guest', 1, 'Band A', 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO metadata_people_evidence
                    (sourceId, trackId, sizeBytes, modifiedAt, sourceSha256,
                     artistCount, artistValues, albumArtistCount, albumArtistValues,
                     receiptVersion)
                VALUES
                    ('peer-b', 'same', 456, 789, '', 1, 'Artist B', 1, 'Band B', 1)
                """.trimIndent(),
            )

            db.query(
                "SELECT sourceId, trackId, sizeBytes, modifiedAt, sourceSha256, " +
                    "artistCount, artistValues, albumArtistCount, albumArtistValues, " +
                    "receiptVersion FROM metadata_people_evidence ORDER BY sourceId",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("peer-a", cursor.getString(0))
                assertEquals("same", cursor.getString(1))
                assertEquals(123L, cursor.getLong(2))
                assertEquals(456L, cursor.getLong(3))
                assertEquals(
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    cursor.getString(4),
                )
                assertEquals(2, cursor.getInt(5))
                assertEquals("Artist A;Guest", cursor.getString(6))
                assertEquals(1, cursor.getInt(7))
                assertEquals("Band A", cursor.getString(8))
                assertEquals(1, cursor.getInt(9))

                assertTrue(cursor.moveToNext())
                assertEquals("peer-b", cursor.getString(0))
                assertEquals("same", cursor.getString(1))
                assertEquals(456L, cursor.getLong(2))
                assertEquals(789L, cursor.getLong(3))
                assertEquals("", cursor.getString(4))
                assertEquals(1, cursor.getInt(5))
                assertEquals("Artist B", cursor.getString(6))
                assertEquals(1, cursor.getInt(7))
                assertEquals("Band B", cursor.getString(8))
                assertEquals(1, cursor.getInt(9))
                assertTrue(!cursor.moveToNext())
            }

            db.query("PRAGMA foreign_key_list(metadata_people_evidence)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("cached_tracks", cursor.getString(2))
                assertEquals("CASCADE", cursor.getString(6))
            }

            // v12 evidence is still readable and unchanged by the upgrade.
            db.query(
                "SELECT genreValues FROM embedded_tag_evidence " +
                    "WHERE sourceId='peer-a' AND trackId='same'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Jazz;Fusion", cursor.getString(0))
            }

            // Evicting one peer's cached track removes only that peer's evidence.
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("DELETE FROM cached_tracks WHERE sourceId='peer-a' AND id='same'")
            db.query(
                "SELECT sourceId FROM metadata_people_evidence ORDER BY sourceId",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("peer-b", cursor.getString(0))
                assertTrue(!cursor.moveToNext())
            }

            // The migration must preserve the existing playlist table.
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='cached_playlists'",
            ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        }
    }
}
