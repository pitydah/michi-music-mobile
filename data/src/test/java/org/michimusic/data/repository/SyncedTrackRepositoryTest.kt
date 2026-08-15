package org.michimusic.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.ManifestPlaylist
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.CachedPlaylist
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.PlaylistDao
import org.michimusic.data.cache.TrackDao

class SyncedTrackRepositoryTest {

    private val mockTrackDao = mockk<TrackDao>(relaxed = true)
    private val mockPlaylistDao = mockk<PlaylistDao>(relaxed = true)
    private val repository = SyncedTrackRepository(mockTrackDao, mockPlaylistDao)

    @Test
    fun `saveLibrary preserves downloaded state and removes obsolete tracks`() = runTest {
        val existingTrackDownloaded = CachedTrack(
            id = "t1", title = "Track 1", downloaded = true, filepath = "/path/t1", artist = "", album = "",
            duration = 0, size = 0, format = "", bitrate = 0, sampleRate = 0, channels = 0, coverId = "",
            trackNumber = 0, year = 0
        )
        val existingTrackNotDownloaded = CachedTrack(
            id = "t2", title = "Track 2", downloaded = false, filepath = "", artist = "", album = "",
            duration = 0, size = 0, format = "", bitrate = 0, sampleRate = 0, channels = 0, coverId = "",
            trackNumber = 0, year = 0
        )
        
        every { mockTrackDao.getAllTracks() } returns flowOf(listOf(existingTrackDownloaded, existingTrackNotDownloaded))

        val newTracksFromNetwork = listOf(
            TrackDto(id = "t1", title = "Track 1 Updated", artist = "", album = "", duration = 0, size = 0, format = "", bitrate = 0, sampleRate = 0, channels = 0),
            TrackDto(id = "t3", title = "Track 3 New", artist = "", album = "", duration = 0, size = 0, format = "", bitrate = 0, sampleRate = 0, channels = 0)
        )

        repository.saveLibrary(newTracksFromNetwork)

        // Verify t1 was updated but kept downloaded=true and filepath
        coVerify { 
            mockTrackDao.insertAll(match<List<CachedTrack>> { list ->
                val t1 = list.find { it.id == "t1" }
                val t3 = list.find { it.id == "t3" }
                t1?.downloaded == true && t1.filepath == "/path/t1" && t1.title == "Track 1 Updated" &&
                t3?.downloaded == false && t3.title == "Track 3 New"
            })
        }

        // Verify t2 was deleted because it's not downloaded and not in the new list
        coVerify { 
            mockTrackDao.delete(match<CachedTrack> { it.id == "t2" })
        }
    }

    @Test
    fun `saveManifestPlaylists replaces all playlists`() = runTest {
        val playlists = listOf(
            ManifestPlaylist(playlistId = "p1", name = "Playlist 1", trackIds = listOf("t1", "t2")),
            ManifestPlaylist(playlistId = "p2", name = "Playlist 2", trackIds = listOf("t3"))
        )

        repository.saveManifestPlaylists(playlists)

        coVerify { mockPlaylistDao.deleteAll() }
        coVerify {
            mockPlaylistDao.insertAll(match<List<CachedPlaylist>> { list ->
                list.size == 2 && 
                list[0].id == "p1" && list[0].trackCount == 2 && list[0].trackIds == "t1,t2" &&
                list[1].id == "p2" && list[1].trackCount == 1 && list[1].trackIds == "t3"
            })
        }
    }

    @Test
    fun `getDownloadedIds returns only downloaded track ids`() = runTest {
        val downloadedTracks = listOf(
            CachedTrack(id = "t1", title = "T1", downloaded = true, filepath = "/p1", artist = "", album = "", duration = 0, size = 0, format = "", bitrate = 0, sampleRate = 0, channels = 0, coverId = "", trackNumber = 0, year = 0),
            CachedTrack(id = "t2", title = "T2", downloaded = true, filepath = "/p2", artist = "", album = "", duration = 0, size = 0, format = "", bitrate = 0, sampleRate = 0, channels = 0, coverId = "", trackNumber = 0, year = 0)
        )
        every { mockTrackDao.getDownloadedTracks() } returns flowOf(downloadedTracks)

        val ids = repository.getDownloadedIds()
        assertEquals(setOf("t1", "t2"), ids)
    }
}
