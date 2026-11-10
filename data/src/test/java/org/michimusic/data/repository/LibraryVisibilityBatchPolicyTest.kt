package org.michimusic.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.data.cache.LibraryExclusionDao
import org.michimusic.data.cache.LibraryExclusionEntity

/** Snapshot validation and all-or-none write contract. No audio deletion API is present. */
class LibraryVisibilityBatchPolicyTest {
    private val a = Track(id = "local_a", title = "A", size = 100L,
        filepath = "content://media/external_primary/audio/media/1")
    private val b = Track(id = "local_b", title = "B", size = 200L,
        filepath = "content://media/external_primary/audio/media/2")

    private fun setup(tracks: List<Track>): Pair<LocalMediaRepository, LibraryExclusionDao> {
        val root = mockk<MusicRootPreferences>()
        every { root.currentRoot() } returns MusicRoot.Default
        val dao = mockk<LibraryExclusionDao>()
        coEvery { dao.forSource(any()) } returns emptyList()
        coEvery { dao.hideBatch(any()) } answers { (firstArg<List<LibraryExclusionEntity>>()).indices.map { it.toLong() + 1 } }
        val repository = object : LocalMediaRepository(
            rootPreferences = root, libraryExclusionDao = dao,
        ) {
            override suspend fun queryTracksResult(): MediaResult<List<Track>> = MediaResult.Success(tracks)
        }
        return repository to dao
    }

    @Test fun exactBatchWritesOnceAndPreservesSource() = runTest {
        val (repo, dao) = setup(listOf(a,b))
        val snapshots = listOf(a,b).map(LocalVisibilitySelection::capture)
        val source = repo.currentVisibilitySource()
        assertEquals(LocalVisibilityBatchResult.Hidden(2),
            repo.hideSelectionFromCurrentLibrary(snapshots, source))
        coVerify(exactly = 1) {
            dao.hideBatch(match { it.size == 2 && it.all { row -> row.sourceId == source } })
        }
    }

    @Test fun aChangedTrackAbortsEntireBatchBeforeRoom() = runTest {
        val (repo, dao) = setup(listOf(a, b.copy(size = 999L)))
        val snapshot = listOf(a,b).map(LocalVisibilitySelection::capture)
        val result = repo.hideSelectionFromCurrentLibrary(snapshot, repo.currentVisibilitySource())
        assertTrue(result is LocalVisibilityBatchResult.Rejected)
        coVerify(exactly = 0) { dao.hideBatch(any()) }
    }

    @Test fun wrongSourceAndDuplicateRejectWithoutWriting() = runTest {
        val (repo, dao) = setup(listOf(a,b))
        assertTrue(repo.hideSelectionFromCurrentLibrary(
            listOf(LocalVisibilitySelection.capture(a)), "stale-root") is LocalVisibilityBatchResult.Rejected)
        assertTrue(repo.hideSelectionFromCurrentLibrary(
            listOf(LocalVisibilitySelection.capture(a),LocalVisibilitySelection.capture(a)),
            repo.currentVisibilitySource()) is LocalVisibilityBatchResult.Rejected)
        coVerify(exactly = 0) { dao.hideBatch(any()) }
    }
}
