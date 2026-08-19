package org.michimusic.data.repository

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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Exercises LocalMediaRepository.loadTracks()'s real caching logic (not a subclass that
// overrides loadTracks() itself). Only queryTracks() - the MediaStore-dependent boundary -
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
        val repo = QueryControlledRepository(listOf(listOf(trackA, trackB)))

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
        val repo = QueryControlledRepository(listOf(emptyList()))

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
        // (SecurityException -> dispatcher returns null), then a second call happens
        // once the permission is actually granted.
        val repo = QueryControlledRepository(listOf(null, listOf(trackA, trackB)))

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
        val repo = QueryControlledRepository(listOf(listOf(trackA), listOf(trackA, trackB)))

        val first = repo.loadTracks()
        assertEquals(listOf(trackA), first)
        assertEquals(1, repo.queryCount)

        repo.invalidateCache()

        val second = repo.loadTracks()
        assertEquals(listOf(trackA, trackB), second)
        assertEquals(2, repo.queryCount)
    }

    companion object {
        private val trackA = Track(id = "a", title = "Song A", artist = "Artist", album = "Album", source = TrackSource.LOCAL)
        private val trackB = Track(id = "b", title = "Song B", artist = "Artist", album = "Album", source = TrackSource.LOCAL)
    }
}

// Stands in for the MediaStore-backed queryTracks(): each call returns the next scripted
// response (null = failed query, emptyList() = valid empty result, list = valid with tracks).
private class QueryControlledRepository(
    responses: List<List<Track>?>,
) : LocalMediaRepository(context = null, replayGainDao = null) {

    private val queue = ArrayDeque(responses)

    var queryCount = 0
        private set

    override suspend fun queryTracks(): List<Track>? {
        queryCount++
        return queue.removeFirst()
    }
}
