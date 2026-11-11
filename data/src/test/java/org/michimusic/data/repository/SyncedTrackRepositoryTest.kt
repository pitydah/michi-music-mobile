package org.michimusic.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `saveLibrary preserves downloaded state and never deletes other owners`() = runTest {
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
        
        // Rows are now read per owner (K16-01), so the stub must answer the
        // source-scoped query the repository actually issues.
        every { mockTrackDao.getTracksForSource("") } returns flowOf(listOf(existingTrackDownloaded, existingTrackNotDownloaded))

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

        // Legacy table is global; a manifest from server A cannot delete server B's row.
        coVerify(exactly = 0) { mockTrackDao.delete(any()) }
    }

    @Test
    fun `saveManifestPlaylists preserves unrelated playlists`() = runTest {
        val playlists = listOf(
            ManifestPlaylist(playlistId = "p1", name = "Playlist 1", trackIds = listOf("t1", "t2")),
            ManifestPlaylist(playlistId = "p2", name = "Playlist 2", trackIds = listOf("t3"))
        )

        repository.saveManifestPlaylists(playlists)

        coVerify(exactly = 0) { mockPlaylistDao.deleteAll() }
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
        every { mockTrackDao.getDownloadedTracksForSource("peer-a") } returns flowOf(downloadedTracks)

        val ids = repository.getDownloadedIds("peer-a")
        assertEquals(setOf("t1", "t2"), ids)
    }

    @Test
    fun `downloadedIds are scoped to the exact server owner`() = runTest {
        val peerA = CachedTrack(
            id = "t1", title = "Track A", sourceId = "peer-a",
            downloaded = true, filepath = "/a/t1.flac",
        )
        every { mockTrackDao.getDownloadedTracksForSource("peer-a") } returns flowOf(listOf(peerA))
        every { mockTrackDao.getDownloadedTracksForSource("peer-b") } returns flowOf(emptyList())

        assertEquals(setOf("t1"), repository.getDownloadedIds("peer-a"))
        assertEquals(emptySet<String>(), repository.getDownloadedIds("peer-b"))
    }

    @Test
    fun `downloaded evidence verifies physical files and never trusts a stale row`() = runTest {
        val real = java.nio.file.Files.createTempFile("michi-sync-evidence", ".flac")
        java.nio.file.Files.write(real, ByteArray(2048))
        try {
            val present = CachedTrack(
                id = "t1", title = "A", artist = "B", duration = 1000, size = 2048,
                downloaded = true, filepath = real.toAbsolutePath().toString(), sourceId = "peer-a",
            )
            val missing = CachedTrack(
                id = "t2", title = "C", artist = "D", duration = 1000, size = 4096,
                downloaded = true, filepath = "/missing/michi-sync.flac", sourceId = "peer-a",
            )
            every { mockTrackDao.getDownloadedTracksForSource("peer-a") } returns
                flowOf(listOf(present, missing))

            val evidence = repository.getDownloadedTrackEvidence("peer-a")
            val verified = evidence.first { it.trackId == "t1" }
            assertTrue(verified.physicallyVerified)
            assertTrue(verified.complete)
            assertEquals(2048L, verified.sizeBytes)
            assertEquals("peer-a", verified.sourceId)

            val stale = evidence.first { it.trackId == "t2" }
            assertFalse(stale.physicallyVerified)
            assertFalse(stale.complete)

            // Legacy managed rows are hashed once and persisted; the evidence
            // then exposes a verifiable checksum instead of only a size.
            assertTrue(verified.managedCopyVerified)
            assertTrue(verified.checksumVerified)
            assertEquals(64, verified.checksum.length)
            assertEquals("sha256", verified.checksumAlgorithm)
            coVerify(exactly = 1) {
                mockTrackDao.markDownloadedVerified(
                    sourceId = "peer-a",
                    id = "t1",
                    filepath = real.toAbsolutePath().toString(),
                    size = 2048L,
                    sha256 = match { it.length == 64 },
                    verifiedAt = any(),
                    algorithm = "sha256",
                )
            }
        } finally {
            java.nio.file.Files.deleteIfExists(real)
        }
    }

    @Test
    fun `SAF local row is never treated as a managed sync copy`() = runTest {
        val real = java.nio.file.Files.createTempFile("michi-saf-local", ".flac")
        java.nio.file.Files.write(real, ByteArray(1024))
        try {
            val saf = CachedTrack(
                id = "saf_1", title = "Local SAF", artist = "A", size = 1024,
                downloaded = true, filepath = real.toAbsolutePath().toString(),
                sourceId = "safroot_tree-1",
            )
            every { mockTrackDao.getDownloadedTracksForSource("safroot_tree-1") } returns flowOf(listOf(saf))

            val evidence = repository.getDownloadedTrackEvidence("safroot_tree-1").single()

            assertTrue(evidence.physicallyVerified)
            assertFalse(evidence.managedCopyVerified)
            assertEquals("", evidence.checksum)
            assertEquals("safroot_tree-1", evidence.sourceId)
        } finally {
            java.nio.file.Files.deleteIfExists(real)
        }
    }

    @Test
    fun `changed managed file invalidates its stored verification before reuse`() = runTest {
        val real = java.nio.file.Files.createTempFile("michi-verification-change", ".flac")
        java.nio.file.Files.write(real, ByteArray(2048))
        try {
            val changed = CachedTrack(
                id = "t1", title = "Changed", artist = "A", size = 2048,
                downloaded = true, filepath = real.toAbsolutePath().toString(), sourceId = "peer-a",
                verifiedSize = 1024,
                verifiedSha256 = "b".repeat(64),
                verifiedAt = System.currentTimeMillis(),
                verifiedAlgorithm = "sha256",
            )
            every { mockTrackDao.getDownloadedTracksForSource("peer-a") } returns flowOf(listOf(changed))

            val evidence = repository.getDownloadedTrackEvidence("peer-a").single()

            assertTrue(evidence.managedCopyVerified)
            assertEquals(2048L, evidence.sizeBytes)
            assertTrue(evidence.checksum != "b".repeat(64))
            coVerify(exactly = 1) {
                mockTrackDao.markDownloadedVerified(
                    sourceId = "peer-a",
                    id = "t1",
                    filepath = real.toAbsolutePath().toString(),
                    size = 2048L,
                    sha256 = match { it.length == 64 && it != "b".repeat(64) },
                    verifiedAt = any(),
                    algorithm = "sha256",
                )
            }
        } finally {
            java.nio.file.Files.deleteIfExists(real)
        }
    }
}
