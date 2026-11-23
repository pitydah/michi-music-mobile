package org.michimusic.data.metadata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataSourceIndexDelta
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.MichiDatabase

/** ME-13 S3/S4: exact Room CAS and conflict refusal without an instrumented run. */
@RunWith(RobolectricTestRunner::class)
class MetadataTrackDaoCasPortTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: MichiDatabase
    private lateinit var dao: org.michimusic.data.cache.TrackDao

    private val sourceId = "lib_device_local"
    private val trackId = "local_1"
    private val uri = "content://media/external_primary/audio/media/1"
    private val originalSha = "a".repeat(64)
    private val candidateSha = "b".repeat(64)
    private val tx = UUID.randomUUID().toString()
    private val delta = MetadataSourceIndexDelta.Delta(
        target = MetadataTarget(sourceId, trackId, uri, "evidence"),
        transactionId = tx, sourceSha256 = candidateSha,
        observedValues = mapOf(MetadataField.TITLE to listOf("Después")),
        untouchedUnknown = emptySet(), candidateBytes = 120L,
    )
    private val permit = MetadataWritePermit(
        transactionId = tx, sourceId = sourceId, trackId = trackId, uri = uri,
        originalSha256 = originalSha, candidateSha256 = candidateSha,
        stageBytes = 120L, profileId = MediaStoreMp3TextProfile.ID,
        evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID,
        container = "ID3_V24", revision = 4, fields = setOf(MetadataField.TITLE),
        explicitUserAction = true,
    )

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = database.trackDao()
    }

    @After fun tearDown() { database.close() }

    private fun row(verifiedAt: Long = 7L) = CachedTrack(
        id = trackId, title = "Antes", artist = "Artista", album = "Álbum",
        sourceId = sourceId, filepath = uri, size = 100L, verifiedSize = 100L,
        verifiedSha256 = originalSha, verifiedAt = verifiedAt, verifiedAlgorithm = "sha256",
        genre = "Rock", trackNumber = 1, year = 1990,
    )

    @Test fun exactVerifiedCasUpdatesTheRowAndInvalidatesEvidence() = runBlocking {
        dao.insertAll(listOf(row()))
        val port = MetadataTrackDaoCasPort(dao, permit)
        val expected = port.current(sourceId, trackId)!!
        assertEquals(7L, expected.version)
        assertEquals(originalSha, expected.observedSourceSha256)
        assertTrue(port.updateIfVersion(expected, delta))
        val after = dao.getTrackBySource(sourceId, trackId)!!
        assertEquals("Después", after.title)
        assertEquals(120L, after.size)
        assertEquals(0L, after.verifiedAt)
        assertEquals("", after.verifiedSha256)
        assertEquals(0L, after.verifiedSize)
    }

    @Test fun concurrentVerificationChangeIsRejectedWithoutOverwrite() = runBlocking {
        dao.insertAll(listOf(row()))
        val port = MetadataTrackDaoCasPort(dao, permit)
        val expected = port.current(sourceId, trackId)!!
        // Simulate a concurrent normal reindex between the read and the CAS.
        dao.insertAll(listOf(row(verifiedAt = 8L)))
        assertFalse(port.updateIfVersion(expected, delta))
        val after = dao.getTrackBySource(sourceId, trackId)!!
        assertEquals("Antes", after.title)
        assertEquals(8L, after.verifiedAt)
        assertEquals(100L, after.size)
    }

    @Test fun aDifferentOwnerOrUriCannotMutate() = runBlocking {
        dao.insertAll(listOf(row()))
        val port = MetadataTrackDaoCasPort(dao, permit)
        val expected = port.current(sourceId, trackId)!!
        assertFalse(port.updateIfVersion(expected.copy(uri = uri + "/other"), delta))
        assertEquals("Antes", dao.getTrackBySource(sourceId, trackId)!!.title)
    }
}
