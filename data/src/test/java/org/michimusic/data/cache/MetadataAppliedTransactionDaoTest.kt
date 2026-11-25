package org.michimusic.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetadataAppliedTransactionDaoTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun scalarCasRevisionAndAppliedMarkerCommitAtomicallyAndAreIdempotent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .addCallback(MichiDatabase.METADATA_REVISION_CALLBACK)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.trackDao()
            val sha = "a".repeat(64)
            val candidateSha = "b".repeat(64)
            dao.insert(
                CachedTrack(
                    id = "track",
                    sourceId = "source",
                    title = "Before",
                    filepath = "content://media/1",
                    size = 100,
                    verifiedSize = 100,
                    verifiedSha256 = sha,
                    verifiedAt = 10,
                    verifiedAlgorithm = "sha256",
                ),
            )
            val before = dao.getTrackBySource("source", "track")!!
            val request = MetadataTrackTransactionRequest(
                transactionId = "00000000-0000-4000-8000-000000000001",
                sourceId = "source",
                trackId = "track",
                filepath = before.filepath,
                previousRevision = before.metadataRevision,
                previousSize = before.size,
                previousVerifiedAt = before.verifiedAt,
                previousSha256 = before.verifiedSha256,
                previousTitle = before.title,
                previousArtist = before.artist,
                previousAlbum = before.album,
                previousAlbumArtist = before.albumArtist,
                previousGenre = before.genre,
                previousTrackNumber = before.trackNumber,
                previousYear = before.year,
                newSize = 120,
                newTitle = "After",
                newArtist = before.artist,
                newAlbum = before.album,
                newAlbumArtist = before.albumArtist,
                newGenre = before.genre,
                newTrackNumber = before.trackNumber,
                newYear = before.year,
                candidateSha256 = candidateSha,
                candidateBytes = 120,
                appliedAt = 99,
            )

            val first = dao.applyMetadataTransaction(request)
            assertTrue(first is MetadataTrackTransactionResult.Applied)
            val applied = first as MetadataTrackTransactionResult.Applied
            assertEquals(before.metadataRevision + 1, applied.marker.committedRevision)
            assertEquals("After", dao.getTrackBySource("source", "track")!!.title)
            assertEquals(
                applied.marker,
                dao.getAppliedMetadataTransaction(request.transactionId),
            )

            val second = dao.applyMetadataTransaction(request)
            assertEquals(MetadataTrackTransactionResult.AlreadyApplied(applied.marker), second)

            val stale = request.copy(
                transactionId = "00000000-0000-4000-8000-000000000002",
                newTitle = "Stale overwrite",
            )
            assertEquals(MetadataTrackTransactionResult.Conflict, dao.applyMetadataTransaction(stale))
            assertEquals("After", dao.getTrackBySource("source", "track")!!.title)
            assertEquals(null, dao.getAppliedMetadataTransaction(stale.transactionId))
        } finally {
            db.close()
        }
    }
}
