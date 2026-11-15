package org.michimusic.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track

class LocalCatalogListenerTest {
    private fun song(id: String) = Track(
        id = id, title = id, artist = "Guest Performer", album = "Compilation",
        albumId = "mediastore-123", albumArtist = "Band A",
    )

    @Test fun onlyFreshFullQueriesEmitAfterSuccessfulRead() = runBlocking {
        val observations = mutableListOf<LocalCatalogObservation>()
        val listener = LocalCatalogListener { observations += it }
        val repository = object : LocalMediaRepository(catalogListener = listener) {
            override suspend fun queryTracksResult(): MediaResult<List<Track>> =
                MediaResult.Success(listOf(song("track-1")))
        }
        val first = repository.fetchTracksResult()
        assertTrue(first is MediaResult.Success)
        assertEquals(1, observations.size)
        assertEquals(DEFAULT_LOCAL_CATALOG_SOURCE, observations.single().sourceId)
        assertEquals(setOf("guest performer", "band a"), observations.single().rows
            .map { it.creditedArtist.lowercase() }.toSet())
        assertEquals(setOf("album_mediastore_mediastore-123"), observations.single().rows
            .map { it.albumKey }.toSet())
        repository.fetchTracksResult() // 30-second cache hit
        assertEquals(1, observations.size)
        repository.invalidateCache()
        repository.fetchTracksResult()
        assertEquals(2, observations.size)
    }

    @Test fun partialCoverageAndFailuresDoNotProduceNewWork() = runBlocking {
        val observations = mutableListOf<LocalCatalogObservation>()
        var response: MediaResult<List<Track>> = MediaResult.Success(
            listOf(song("track-1")), MediaCoverage.SAF_ONLY_PERMISSION_DENIED,
        )
        val repository = object : LocalMediaRepository(
            catalogListener = LocalCatalogListener { observations += it },
        ) {
            override suspend fun queryTracksResult(): MediaResult<List<Track>> = response
        }
        repository.fetchTracksResult()
        assertTrue(observations.isEmpty())
        repository.invalidateCache()
        response = MediaResult.QueryFailed(IllegalStateException("provider unavailable"))
        repository.fetchTracksResult()
        assertTrue(observations.isEmpty())
    }

    @Test fun optOutSkipsAllCatalogNotifications() = runBlocking {
        var events = 0
        val listener = object : LocalCatalogListener {
            override fun isEnabled() = false
            override suspend fun onCatalogRead(observation: LocalCatalogObservation) { events++ }
        }
        val repository = object : LocalMediaRepository(catalogListener = listener) {
            override suspend fun queryTracksResult(): MediaResult<List<Track>> =
                MediaResult.Success(listOf(song("track-1")))
        }
        repository.fetchTracksResult()
        assertEquals(0, events)
    }
}
