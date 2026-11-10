package org.michimusic.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.LibraryExclusionDao
import org.michimusic.data.cache.LibraryExclusionEntity

class LibraryVisibilityRepositoryTest {
    @Test fun hideSurvivesCacheInvalidationAndRestoreDoesNotDeleteAudio() = runTest {
        val uri = "content://media/external_primary/audio/media/41"
        val track = Track(id = "local_41", title = "Canción", artist = "Artista",
            album = "Álbum", size = 8000, filepath = uri, duration = 120000)
        val preferences = mockk<MusicRootPreferences>()
        every { preferences.currentRoot() } returns MusicRoot.Default
        val dao = mockk<LibraryExclusionDao>()
        var excluded: LibraryExclusionEntity? = null
        coEvery { dao.forSource(any()) } answers { listOfNotNull(excluded) }
        coEvery { dao.hide(any()) } answers { excluded = firstArg(); 1L }
        coEvery { dao.restore(any(), any()) } answers { excluded = null; 1 }
        val repository = object : LocalMediaRepository(
            rootPreferences = preferences, libraryExclusionDao = dao,
        ) {
            override suspend fun queryTracksResult(): MediaResult<List<Track>> =
                MediaResult.Success(listOf(track))
        }

        assertEquals(listOf(track), repository.loadTracks())
        assertEquals(LocalVisibilityResult.Hidden,
            repository.hideFromCurrentLibrary(track.id, uri))
        assertTrue(repository.loadTracks().isEmpty())
        repository.invalidateCache() // Simulates a fresh index without erasing the exclusion.
        assertTrue(repository.loadTracks().isEmpty())
        val source = libraryVisibilitySource(MusicRoot.Default)
        assertEquals(LocalVisibilityResult.Restored,
            repository.restoreInCurrentLibrary(track.id, source))
        assertEquals(listOf(track), repository.loadTracks())
        assertEquals(LocalVisibilityResult.Rejected("La biblioteca cambió. No se restauró otra fuente."),
            repository.restoreInCurrentLibrary(track.id, "another-source"))
    }

    @Test fun syncedCacheCannotResurrectHiddenActiveSourceTrack() = runTest {
        val uri = "content://media/external_primary/audio/media/42"
        val track = Track(id = "local_42", title = "Canción", artist = "Artista",
            album = "Álbum", size = 9000, filepath = uri, duration = 180000)
        val preferences = mockk<MusicRootPreferences>()
        every { preferences.currentRoot() } returns MusicRoot.Default
        val source = libraryVisibilitySource(MusicRoot.Default)
        val exclusion = LibraryExclusionEntity(source, track.id,
            libraryVisibilityFingerprint(track), track.title, track.artist, track.album)
        val dao = mockk<LibraryExclusionDao>()
        coEvery { dao.forSource(any()) } returns listOf(exclusion)
        val repository = object : LocalMediaRepository(
            rootPreferences = preferences, libraryExclusionDao = dao,
        ) {
            override suspend fun queryTracksResult(): MediaResult<List<Track>> =
                MediaResult.Success(listOf(track))
        }
        val activeCache = CachedTrack(
            id = track.id, title = track.title, artist = track.artist, album = track.album,
            duration = track.duration, size = track.size, filepath = track.filepath,
            sourceId = source,
        )
        val foreignCache = activeCache.copy(id = "local_99", sourceId = "remote-peer")
        assertTrue(repository.filterHiddenSynced(listOf(activeCache)).isEmpty())
        assertEquals(listOf(foreignCache), repository.filterHiddenSynced(listOf(foreignCache)))
    }
}
