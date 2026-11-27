package org.michimusic.data.metadata

import org.michimusic.core.metadata.MetadataRecoveryPhase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.cache.MetadataAppliedTransaction
import org.michimusic.data.cache.MichiDatabase
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetadataAppliedMarkerRetentionTest {
    private lateinit var context: Context
    private lateinit var database: MichiDatabase
    private lateinit var root: File
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .addCallback(MichiDatabase.METADATA_REVISION_CALLBACK)
            .allowMainThreadQueries().build()
        root = Files.createTempDirectory("me-titan-markers-").toFile()
    }

    @After fun cleanup() {
        database.close()
        root.deleteRecursively()
    }

    private fun marker(id: String, appliedAt: Long) = MetadataAppliedTransaction(
        transactionId = id,
        sourceId = "source",
        trackId = "track",
        uri = "content://media/1",
        originalRevision = 0,
        committedRevision = 1,
        candidateSha256 = "a".repeat(64),
        candidateBytes = 100,
        appliedAt = appliedAt,
    )

    @Test fun prunesOnlyOldUnprotectedMarkersAndKeepsPendingJournal() = runBlocking {
        val oldId = UUID.randomUUID().toString()
        val recentId = UUID.randomUUID().toString()
        val pendingId = UUID.randomUUID().toString()
        val journal = MetadataRecoveryJournalV1(root)
        journal.exclusive {
            journal.start(MetadataRecoveryJournalV1.Entry(
                pendingId, MetadataRecoveryPhase.SNAPSHOTTING, "source", "track",
                "content://media/1", 4096, 777,
            ))
        }
        val dao = database.trackDao()
        dao.insertAppliedMetadataTransaction(marker(oldId, now - 31 * day))
        dao.insertAppliedMetadataTransaction(marker(recentId, now - day))
        dao.insertAppliedMetadataTransaction(marker(pendingId, now - 31 * day))

        val first = MetadataAppliedMarkerRetention(journal, dao, root).prune(now)
        assertEquals(1, first)
        assertNull(dao.getAppliedMetadataTransaction(oldId))
        assertNotNull(dao.getAppliedMetadataTransaction(recentId))
        assertNotNull(dao.getAppliedMetadataTransaction(pendingId))
        assertEquals(0, MetadataAppliedMarkerRetention(journal, dao, root).prune(now))
    }

    @Test fun corruptJournalDeletesNothing() = runBlocking {
        File(root, "journal-v1.bin").writeText("garbage")
        val journal = MetadataRecoveryJournalV1(root)
        val dao = database.trackDao()
        dao.insertAppliedMetadataTransaction(marker("old", now - 31 * day))

        assertEquals(0, MetadataAppliedMarkerRetention(journal, dao, root).prune(now))
        assertNotNull(dao.getAppliedMetadataTransaction("old"))
    }
}
