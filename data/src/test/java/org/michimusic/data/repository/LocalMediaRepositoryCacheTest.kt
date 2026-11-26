package org.michimusic.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.core.metadata.MetadataCommittedLibraryChange
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.MetadataAppliedTransaction
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.cache.TrackDao
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Exercises LocalMediaRepository.loadTracks()'s real caching logic (not a subclass that
// overrides loadTracks() itself). Only queryTracksResult() - the MediaStore-dependent boundary -
// is substituted, so the cache-hit/miss/invalidate behavior under test is the real thing.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalMediaRepositoryCacheTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadTracks_validResultWithTracks_isCachedOnSecondCall() = runTest(testDispatcher) {
        val repo = QueryControlledRepository(listOf(MediaResult.Success(listOf(trackA, trackB))))

        val first = repo.loadTracks()
        assertEquals(listOf(trackA, trackB), first)
        assertEquals(1, repo.queryCount)

        val second = repo.loadTracks()
        assertEquals(listOf(trackA, trackB), second)
        assertEquals(1, repo.queryCount) // served from cache, no second query
    }

    @Test
    fun loadTracks_validEmptyResult_isCachedOnSecondCall() = runTest(testDispatcher) {
        // cursor != null, 0 rows: a legitimate empty library, safe to cache.
        val repo = QueryControlledRepository(listOf(MediaResult.Success(emptyList())))

        val first = repo.loadTracks()
        assertTrue(first.isEmpty())
        assertEquals(1, repo.queryCount)

        val second = repo.loadTracks()
        assertTrue(second.isEmpty())
        assertEquals(1, repo.queryCount)
    }

    @Test
    fun loadTracks_failedQueryThenGrantedPermission_reQueriesAndReturnsTracksImmediately() = runTest(testDispatcher) {
        // Reproduces the device bug: first call races ahead of the permission grant
        // (SecurityException -> dispatcher returns PermissionDenied), then a second call happens
        // once the permission is actually granted.
        val repo = QueryControlledRepository(listOf(MediaResult.PermissionDenied, MediaResult.Success(listOf(trackA, trackB))))

        val first = repo.loadTracks()
        assertTrue("a failed query must surface as an empty list, not crash", first.isEmpty())
        assertEquals(1, repo.queryCount)

        val second = repo.loadTracks()
        assertEquals(
            "the failed query must not have been cached, so this call re-queries and " +
                "the real tracks appear immediately",
            listOf(trackA, trackB),
            second,
        )
        assertEquals(2, repo.queryCount)
    }

    @Test
    fun invalidateCache_forcesRequeryOnNextLoad() = runTest(testDispatcher) {
        val repo = QueryControlledRepository(listOf(MediaResult.Success(listOf(trackA)), MediaResult.Success(listOf(trackA, trackB))))

        val first = repo.loadTracks()
        assertEquals(listOf(trackA), first)
        assertEquals(1, repo.queryCount)

        repo.invalidateCache()

        val second = repo.loadTracks()
        assertEquals(listOf(trackA, trackB), second)
        assertEquals(2, repo.queryCount)
    }

    @Test
    fun fetchTracksResult_returnsPermissionDenied_whenQueryFailsWithPermission() = runTest(testDispatcher) {
        val repo = QueryControlledRepository(listOf(MediaResult.PermissionDenied))
        val result = repo.fetchTracksResult()
        assertTrue(result is MediaResult.PermissionDenied)
    }

    @Test
    fun committedMetadataPatchesCachedTrackAndRegroupsWithoutMediaStoreQuery() =
        runTest(testDispatcher) {
            val original = trackA.copy(
                id = "local_7",
                album = "Old",
                albumId = "42",
                filepath = "content://media/external/audio/media/7",
            )
            val database = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                MichiDatabase::class.java,
            ).allowMainThreadQueries().build()
            try {
                val change = committedChange(original)
                database.trackDao().insertAll(listOf(
                    CachedTrack(
                        id = original.id,
                        title = original.title,
                        album = "Old",
                        filepath = original.filepath,
                        sourceId = change.sourceId,
                        size = change.committedBytes,
                        metadataRevision = change.committedRevision,
                    ),
                ))
                database.trackDao().insertAppliedMetadataTransaction(marker(change))
                val repo = QueryControlledRepository(
                    listOf(MediaResult.Success(listOf(original))),
                    database.trackDao(),
                )
                repo.loadTracks()

                val result = repo.applyMetadataCommittedChange(change)

                assertTrue(result is LocalMediaRepository.MetadataCachePatch.Applied)
                val applied = result as LocalMediaRepository.MetadataCachePatch.Applied
                assertEquals("New", applied.changedTrack.album)
                assertEquals("", applied.changedTrack.albumId)
                assertEquals("New", applied.albums.single().album.title)
                assertEquals(1, repo.queryCount)
                assertEquals("New", repo.loadTracks().single().album)
                assertEquals(1, repo.queryCount)
            } finally {
                database.close()
            }
        }

    @Test
    fun committedMetadataWithoutItsMarkerIsRefused() = runTest(testDispatcher) {
        val original = trackA.copy(
            id = "local_7",
            filepath = "content://media/external/audio/media/7",
        )
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val change = committedChange(original)
            database.trackDao().insertAll(listOf(
                CachedTrack(
                    id = original.id,
                    title = original.title,
                    filepath = original.filepath,
                    sourceId = change.sourceId,
                    size = change.committedBytes,
                    metadataRevision = change.committedRevision,
                ),
            ))
            val repo = QueryControlledRepository(
                listOf(MediaResult.Success(listOf(original))),
                database.trackDao(),
            )
            repo.loadTracks()

            val result = repo.applyMetadataCommittedChange(change)

            assertTrue(result is LocalMediaRepository.MetadataCachePatch.Refused)
            assertEquals(
                "COMMITTED_EVIDENCE_REQUIRED",
                (result as LocalMediaRepository.MetadataCachePatch.Refused).reason,
            )
            assertEquals("Album", repo.loadTracks().single().album)
        } finally {
            database.close()
        }
    }

    @Test
    fun roomUnavailableAfterWriteRefusesTheInMemoryPatch() = runTest(testDispatcher) {
        val original = trackA.copy(
            id = "local_7",
            filepath = "content://media/external/audio/media/7",
        )
        val repo = QueryControlledRepository(
            listOf(MediaResult.Success(listOf(original))),
            unavailableTrackDao(),
        )
        repo.loadTracks()

        val result = repo.applyMetadataCommittedChange(committedChange(original))

        assertTrue(result is LocalMediaRepository.MetadataCachePatch.Refused)
        assertEquals(
            "DURABLE_EVIDENCE_UNAVAILABLE",
            (result as LocalMediaRepository.MetadataCachePatch.Refused).reason,
        )
        assertEquals("Album", repo.loadTracks().single().album)
    }

    private fun unavailableTrackDao(): TrackDao = Proxy.newProxyInstance(
        TrackDao::class.java.classLoader,
        arrayOf(TrackDao::class.java),
    ) { _, method, _ ->
        if (method.name == "getAppliedMetadataTransaction" ||
            method.name == "getTrackBySource"
        ) {
            throw IllegalStateException("Room unavailable after write")
        }
        throw UnsupportedOperationException(method.name)
    } as TrackDao

    private fun committedChange(original: Track) = MetadataCommittedLibraryChange(
        transactionId = "12341234-1234-4abc-8abc-123412341234",
        sourceId = "lib_device_local",
        trackId = original.id,
        uri = original.filepath,
        changedFields = setOf(MetadataField.ALBUM),
        resourceOperations = emptySet(),
        snapshot = MetadataSnapshot(
            target = MetadataTarget(
                "lib_device_local", original.id, original.filepath, "cold",
            ),
            tagsVerified = true,
            known = mapOf(MetadataField.ALBUM to listOf("New")),
        ),
        previousRevision = 1,
        committedRevision = 2,
        committedBytes = 123,
        committedSha256 = "b".repeat(64),
    )

    private fun marker(change: MetadataCommittedLibraryChange) = MetadataAppliedTransaction(
        transactionId = change.transactionId,
        sourceId = change.sourceId,
        trackId = change.trackId,
        uri = change.uri,
        originalRevision = change.previousRevision,
        committedRevision = change.committedRevision,
        candidateSha256 = change.committedSha256,
        candidateBytes = change.committedBytes,
        appliedAt = 1L,
    )

    companion object {
        private val trackA = Track(id = "a", title = "Song A", artist = "Artist", album = "Album", source = TrackSource.LOCAL)
        private val trackB = Track(id = "b", title = "Song B", artist = "Artist", album = "Album", source = TrackSource.LOCAL)
    }
}

// Stands in for the MediaStore-backed queryTracksResult(): each call returns the next scripted
// response (PermissionDenied = failed query, Success(emptyList()) = valid empty result, Success(list) = valid with tracks).
private class QueryControlledRepository(
    responses: List<MediaResult<List<Track>>>,
    importedTrackDao: TrackDao? = null,
) : LocalMediaRepository(
    context = null,
    replayGainDao = null,
    importedTrackDao = importedTrackDao,
) {

    private val queue = ArrayDeque(responses)

    var queryCount = 0
        private set

    override suspend fun queryTracksResult(): MediaResult<List<Track>> {
        queryCount++
        return queue.removeFirst()
    }
}
