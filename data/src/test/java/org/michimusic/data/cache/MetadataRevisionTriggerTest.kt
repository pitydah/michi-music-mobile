package org.michimusic.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetadataRevisionTriggerTest {
    private lateinit var db: MichiDatabase
    private val source = "source"
    private val track = "track"
    private val uri = "content://media/1"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MichiDatabase::class.java,
        ).addCallback(MichiDatabase.METADATA_REVISION_CALLBACK)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun row() = CachedTrack(
        id = track, title = "Antes", artist = "Artista", album = "Álbum",
        sourceId = source, filepath = uri, size = 100L, verifiedSize = 100L,
        verifiedSha256 = "a".repeat(64), verifiedAt = 7L,
        verifiedAlgorithm = "sha256", genre = "Rock", trackNumber = 1, year = 1990,
    )

    private fun rowRevision(database: MichiDatabase = db): Long = scalar(
        database,
        "SELECT `metadataRevision` FROM `cached_tracks` " +
            "WHERE `sourceId`='$source' AND `id`='$track'",
    )

    private fun clockRevision(database: MichiDatabase = db): Long = scalar(
        database,
        "SELECT `revision` FROM `metadata_revision_clock` " +
            "WHERE `sourceId`='$source' AND `trackId`='$track'",
    )

    private fun scalar(database: MichiDatabase, sql: String): Long =
        database.openHelper.writableDatabase.query(sql).use { cursor ->
            assertTrue("expected a row for: $sql", cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun assertClockDoesNotLagRow(database: MichiDatabase = db) {
        val published = rowRevision(database)
        val clock = clockRevision(database)
        assertTrue(
            "clock $clock must never lag the published revision $published",
            clock >= published,
        )
    }

    @Test fun explicitRevisionLoweringIsAbortedAndRevisionStaysMonotonic() = runBlocking {
        db.trackDao().insertAll(listOf(row()))
        val before = db.trackDao().getTrackBySource(source, track)!!
        var threw = false
        try {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE cached_tracks SET metadataRevision=metadataRevision-1 " +
                    "WHERE sourceId='$source' AND id='$track'",
            )
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("lowering metadataRevision must be rejected", threw)
        assertEquals(
            before.metadataRevision,
            db.trackDao().getTrackBySource(source, track)!!.metadataRevision,
        )
    }

    @Test fun deleteThenInsertAdvancesRevision() = runBlocking {
        db.trackDao().insertAll(listOf(row()))
        val first = db.trackDao().getTrackBySource(source, track)!!
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM cached_tracks WHERE sourceId='$source' AND id='$track'",
        )
        db.trackDao().insertAll(listOf(row()))
        val second = db.trackDao().getTrackBySource(source, track)!!
        assertTrue(
            "delete+insert must advance revision: first=${first.metadataRevision} " +
                "second=${second.metadataRevision}",
            second.metadataRevision > first.metadataRevision,
        )
    }

    @Test fun explicitIncreaseThenOrdinaryUpdateAndReinsertStayMonotonic() = runBlocking {
        db.trackDao().insertAll(listOf(row()))
        assertEquals("fresh insert publishes revision 1", 1L, rowRevision())
        assertEquals(1L, clockRevision())

        // An explicit advance, as a CAS commit or a recovery restore performs it, used to leave the
        // clock behind the row and made the next ordinary write regress (or abort) instead.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE cached_tracks SET metadataRevision=5 " +
                "WHERE sourceId='$source' AND id='$track'",
        )
        assertEquals("explicit advance is published as written", 5L, rowRevision())
        assertEquals("clock must follow an explicit advance", 5L, clockRevision())

        // The next ordinary write must land strictly above the published revision.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE cached_tracks SET title='Después' " +
                "WHERE sourceId='$source' AND id='$track'",
        )
        assertEquals(6L, rowRevision())
        assertEquals(6L, clockRevision())

        // A REPLACE insert of the same primary key is the cached UPSERT path.
        db.trackDao().insertAll(listOf(row()))
        val upserted = rowRevision()
        assertTrue("REPLACE must advance past $upserted-1", upserted > 6L)
        assertEquals(upserted, clockRevision())
        assertClockDoesNotLagRow()

        // Explicit delete followed by reinsert keeps advancing as well.
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM cached_tracks WHERE sourceId='$source' AND id='$track'",
        )
        val afterDelete = clockRevision()
        assertTrue("delete must advance the clock past $upserted", afterDelete > upserted)
        db.trackDao().insertAll(listOf(row()))
        val reinserted = rowRevision()
        assertTrue("reinsert must advance past the delete", reinserted > afterDelete)
        assertEquals(reinserted, clockRevision())
        assertClockDoesNotLagRow()
    }

    @Test fun staleTriggerDefinitionsAreUpgradedOnOpen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "metadata-revision-upgrade.db"
        context.deleteDatabase(name)
        try {
            val shipped = Room.databaseBuilder(context, MichiDatabase::class.java, name)
                .addCallback(MichiDatabase.METADATA_REVISION_CALLBACK)
                .allowMainThreadQueries().build()
            try {
                shipped.trackDao().insertAll(listOf(row()))
                // Reproduce an installation still carrying the previous trigger bodies: only an
                // ordinary update moved the clock, so an explicit advance could overtake it.
                shipped.openHelper.writableDatabase.execSQL(
                    "DROP TRIGGER `cached_tracks_revision_after_explicit_advance`",
                )
                shipped.openHelper.writableDatabase.execSQL(
                    "DROP TRIGGER `cached_tracks_revision_after_update`",
                )
                shipped.openHelper.writableDatabase.execSQL("""
                    CREATE TRIGGER `cached_tracks_revision_after_update`
                    AFTER UPDATE ON `cached_tracks`
                    WHEN NEW.`metadataRevision`=OLD.`metadataRevision`
                    BEGIN
                        INSERT INTO `metadata_revision_clock`(`sourceId`,`trackId`,`revision`)
                        VALUES(NEW.`sourceId`,NEW.`id`,1)
                        ON CONFLICT(`sourceId`,`trackId`) DO UPDATE
                        SET `revision`=`revision`+1;
                        UPDATE `cached_tracks`
                        SET `metadataRevision`=(
                            SELECT `revision` FROM `metadata_revision_clock`
                            WHERE `sourceId`=NEW.`sourceId` AND `trackId`=NEW.`id`
                        )
                        WHERE `sourceId`=NEW.`sourceId` AND `id`=NEW.`id`;
                    END
                """.trimIndent())
                shipped.openHelper.writableDatabase.execSQL(
                    "UPDATE cached_tracks SET metadataRevision=5 " +
                        "WHERE sourceId='$source' AND id='$track'",
                )
                assertEquals(5L, rowRevision(shipped))
                assertEquals("previous definitions let the clock lag", 1L, clockRevision(shipped))
            } finally {
                shipped.close()
            }

            val reopened = Room.databaseBuilder(context, MichiDatabase::class.java, name)
                .addCallback(MichiDatabase.METADATA_REVISION_CALLBACK)
                .allowMainThreadQueries().build()
            try {
                assertEquals("onOpen repairs the lagging clock", 5L, clockRevision(reopened))
                // The corrected bodies are installed too: an ordinary write still advances.
                reopened.openHelper.writableDatabase.execSQL(
                    "UPDATE cached_tracks SET title='Después' " +
                        "WHERE sourceId='$source' AND id='$track'",
                )
                assertEquals(6L, rowRevision(reopened))
                assertEquals(6L, clockRevision(reopened))
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
