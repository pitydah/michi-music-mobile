package org.michimusic.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.DeliveryChoice
import org.michimusic.core.models.DeliveryProfile
import org.michimusic.core.models.ManifestTrack
import org.michimusic.core.models.SyncItemAction
import org.michimusic.core.models.SyncPreflightStatus
import org.michimusic.core.models.SyncScope
import org.michimusic.core.models.SyncSelection

class SyncPreflightServiceTest {

    private val service = SyncPreflightService()
    private val oneMb = 1024L * 1024L

    private val trackA = ManifestTrack(
        trackId = "trk-1",
        title = "Track One",
        artist = "Artist A",
        album = "Album Alpha",
        size = 10 * oneMb,
        checksum = "hash-1",
    )

    private val trackB = ManifestTrack(
        trackId = "trk-2",
        title = "Track Two",
        artist = "Artist B",
        album = "Album Beta",
        size = 12 * oneMb,
        checksum = "hash-2",
    )

    private val trackC = ManifestTrack(
        trackId = "trk-3",
        title = "Track Three",
        artist = "Artist C",
        album = "Album Gamma",
        size = 15 * oneMb,
        checksum = "hash-3",
    )

    @Test
    fun emptySelection_blocksWithEmptyStatus() {
        val selection = SyncSelection(scope = SyncScope.TRACKS, trackIds = emptySet())
        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA, trackB),
        )

        assertEquals(SyncPreflightStatus.BLOCKED_EMPTY_SELECTION, plan.status)
        assertFalse(plan.isExecutable)
        assertEquals(0, plan.totalSelectedTracks)
    }

    @Test
    fun deduplicatesTracksAndClassifiesActionsCorrectly() {
        // Selection includes track 1, 2, 3 and a duplicate of track 1
        val selection = SyncSelection(
            scope = SyncScope.TRACKS,
            trackIds = setOf("trk-1", "trk-2", "trk-3")
        )

        // trk-1 is already in local library
        val localFingerprints = setOf("trk-1")

        // trk-2 has 4 MB in .part file
        val partials = mapOf("trk-2" to 4 * oneMb)

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server",
            selection = selection,
            deliveryChoice = DeliveryChoice(profile = DeliveryProfile.ORIGINAL),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA, trackB, trackC, trackA), // duplicates trackA
            localTrackFingerprints = localFingerprints,
            existingPartials = partials,
        )

        assertEquals(3, plan.totalSelectedTracks)
        assertEquals(1, plan.tracksSkippedCount) // trk-1
        assertEquals(1, plan.tracksToResumeCount) // trk-2
        assertEquals(1, plan.tracksToDownloadCount) // trk-3

        // Bytes to download: trk-1 (0) + trk-2 (12-4 = 8 MB) + trk-3 (15 MB) = 23 MB
        assertEquals(23 * oneMb, plan.totalDownloadBytes)
        assertEquals(SyncPreflightStatus.READY, plan.status)
        assertTrue(plan.isExecutable)

        val item1 = plan.items.first { it.trackId == "trk-1" }
        assertEquals(SyncItemAction.SKIP_ALREADY_EXISTS, item1.action)
        assertEquals(0L, item1.estimatedDownloadBytes)

        val item2 = plan.items.first { it.trackId == "trk-2" }
        assertEquals(SyncItemAction.RESUME_PARTIAL, item2.action)
        assertEquals(8 * oneMb, item2.estimatedDownloadBytes)

        val item3 = plan.items.first { it.trackId == "trk-3" }
        assertEquals(SyncItemAction.DOWNLOAD, item3.action)
        assertEquals(15 * oneMb, item3.estimatedDownloadBytes)
    }

    @Test
    fun allTracksAlreadySynced_emitsWarningAndZeroDownloadBytes() {
        val selection = SyncSelection(scope = SyncScope.FULL_LIBRARY)
        val localFingerprints = setOf("trk-1", "trk-2", "trk-3")

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA, trackB, trackC),
            localTrackFingerprints = localFingerprints,
        )

        assertEquals(3, plan.totalSelectedTracks)
        assertEquals(3, plan.tracksSkippedCount)
        assertEquals(0, plan.tracksToDownloadCount)
        assertEquals(0L, plan.totalDownloadBytes)
        assertEquals(SyncPreflightStatus.READY, plan.status)
        assertTrue(plan.issues.any { it.code == "ALL_TRACKS_ALREADY_SYNCED" })
    }

    @Test
    fun insufficientStorage_blocksPlan() {
        val selection = SyncSelection(scope = SyncScope.FULL_LIBRARY)
        // track A + B + C = 37 MB. With 100 MB safety buffer + 10 MB atomic buffer = 147 MB required.
        // If available is only 120 MB:
        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 120 * oneMb,
            candidateTracks = listOf(trackA, trackB, trackC),
        )

        assertEquals(SyncPreflightStatus.BLOCKED_NO_STORAGE, plan.status)
        assertFalse(plan.isExecutable)
    }

    @Test
    fun fullLibraryWithEmptyCandidates_blocksWithEmptyStatus() {
        val selection = SyncSelection(scope = SyncScope.FULL_LIBRARY)
        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = emptyList(),
        )

        assertEquals(SyncPreflightStatus.BLOCKED_EMPTY_SELECTION, plan.status)
        assertFalse(plan.isExecutable)
        assertEquals(0, plan.totalSelectedTracks)
    }

    @Test
    fun albumSelection_filtersTracksBelongingToSelectedAlbums() {
        val selection = SyncSelection(
            scope = SyncScope.ALBUMS,
            albumIds = setOf("Album Alpha"),
        )

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA, trackB, trackC),
        )

        assertEquals(1, plan.totalSelectedTracks)
        assertEquals("trk-1", plan.items.first().trackId)
        assertEquals(10 * oneMb, plan.totalDownloadBytes)
        assertTrue(plan.isExecutable)
    }

    @Test
    fun playlistSelection_filtersTracksFromManifestPlaylists() {
        val manifestPlaylists = listOf(
            org.michimusic.core.models.ManifestPlaylist(
                playlistId = "pl-synth",
                name = "Synthwave",
                trackIds = listOf("trk-2", "trk-3"),
            )
        )
        val selection = SyncSelection(
            scope = SyncScope.PLAYLISTS,
            playlistIds = setOf("pl-synth"),
        )

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA, trackB, trackC),
            manifestPlaylists = manifestPlaylists,
        )

        assertEquals(2, plan.totalSelectedTracks)
        assertEquals(setOf("trk-2", "trk-3"), plan.items.map { it.trackId }.toSet())
        assertTrue(plan.isExecutable)
    }

    @Test
    fun transcodedProfile_estimatesDownloadBytesByDurationRate() {
        // trackA has 200 seconds duration, size is 10 MB
        val trackWithDuration = trackA.copy(duration = 200)
        val deliveryChoice = DeliveryChoice(
            profile = DeliveryProfile.BALANCED,
            codec = "aac",
            maxBitrateKbps = 256,
            isServerTranscoded = true,
        )
        val selection = SyncSelection(scope = SyncScope.TRACKS, trackIds = setOf("trk-1"))

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server",
            selection = selection,
            deliveryChoice = deliveryChoice,
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackWithDuration),
        )

        // 200s * 32,000 bytes/s = 6,400,000 bytes (< 10 MB original)
        val expectedTranscodedBytes = 200L * 32_000L
        assertEquals(expectedTranscodedBytes, plan.totalDownloadBytes)
        assertEquals(expectedTranscodedBytes, plan.items.first().estimatedDownloadBytes)
    }
}
