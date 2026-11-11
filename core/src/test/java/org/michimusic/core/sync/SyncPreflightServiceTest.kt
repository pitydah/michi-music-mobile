package org.michimusic.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.DeliveryChoice
import org.michimusic.core.models.DeliveryProfile
import org.michimusic.core.models.LocalSyncTrackEvidence
import org.michimusic.core.models.ManifestTrack
import org.michimusic.core.models.SyncIdentityVerdict
import org.michimusic.core.models.SyncItemAction
import org.michimusic.core.models.SyncPreflightStatus
import org.michimusic.core.models.SyncScope
import org.michimusic.core.models.SyncSelection

class SyncPreflightServiceTest {

    private val service = SyncPreflightService()
    private val oneMb = 1024L * 1024L

    private val sha1 = "1".repeat(64)
    private val sha2 = "2".repeat(64)
    private val sha3 = "3".repeat(64)

    private val trackA = ManifestTrack(
        trackId = "trk-1",
        title = "Track One",
        artist = "Artist A",
        album = "Album Alpha",
        size = 10 * oneMb,
        checksum = sha1,
    )

    private val trackB = ManifestTrack(
        trackId = "trk-2",
        title = "Track Two",
        artist = "Artist B",
        album = "Album Beta",
        size = 12 * oneMb,
        checksum = sha2,
    )

    private val trackC = ManifestTrack(
        trackId = "trk-3",
        title = "Track Three",
        artist = "Artist C",
        album = "Album Gamma",
        size = 15 * oneMb,
        checksum = sha3,
    )

    private fun verified(vararg tracks: ManifestTrack, sourceId: String = "ep-1") = tracks.map {
        LocalSyncTrackEvidence(
            sourceId = sourceId,
            trackId = it.trackId,
            title = it.title,
            artist = it.artist,
            duration = it.duration.toLong(),
            sizeBytes = it.size,
            checksum = it.checksum,
            checksumVerified = it.checksum.isNotEmpty(),
            checksumAlgorithm = "sha256",
            managedCopyVerified = true,
            filePath = "/music/${it.trackId}.flac",
            physicallyVerified = true,
            complete = true,
        )
    }

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

        // trk-1 has a physically verified copy owned by this same server
        val localEvidence = verified(trackA)

        // trk-2 has 4 MB in .part file
        val partials = mapOf("trk-2" to 4 * oneMb)

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server",
            selection = selection,
            deliveryChoice = DeliveryChoice(profile = DeliveryProfile.ORIGINAL),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA, trackB, trackC, trackA), // duplicates trackA
            localTrackEvidence = localEvidence,
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
        assertEquals(SyncIdentityVerdict.HASH_VERIFIED, item1.identity)
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
        val localEvidence = verified(trackA, trackB, trackC)

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA, trackB, trackC),
            localTrackEvidence = localEvidence,
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

    @Test
    fun sameTrackIdFromAnotherServer_isDifferentSourceAndNeverSkipped() {
        val selection = SyncSelection(scope = SyncScope.TRACKS, trackIds = setOf("trk-1"))
        val foreign = verified(trackA, sourceId = "ep-2").single()

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server A",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA),
            localTrackEvidence = listOf(foreign),
        )

        val item = plan.items.single()
        assertEquals(SyncItemAction.DOWNLOAD, item.action)
        assertEquals(SyncIdentityVerdict.DIFFERENT_SOURCE, item.identity)
        assertEquals("ep-2", item.matchedSourceId)
        assertEquals(1, plan.tracksToDownloadCount)
    }

    @Test
    fun metadataOnlyMatch_isPossibleDuplicateAndNeverSkipped() {
        val selection = SyncSelection(scope = SyncScope.TRACKS, trackIds = setOf("trk-1"))
        val metadataOnly = LocalSyncTrackEvidence(
            sourceId = "ep-2",
            trackId = "different-id",
            title = trackA.title,
            artist = trackA.artist,
            duration = 0,
            sizeBytes = 10 * oneMb,
            filePath = "/music/other.flac",
            physicallyVerified = true,
            complete = true,
        )

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server A",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA),
            localTrackEvidence = listOf(metadataOnly),
        )

        val item = plan.items.single()
        assertEquals(SyncItemAction.DOWNLOAD, item.action)
        assertEquals(SyncIdentityVerdict.POSSIBLE_DUPLICATE, item.identity)
    }

    @Test
    fun downloadedRowWithMissingPhysicalFile_isNotVerifiedAndDownloadsAgain() {
        val selection = SyncSelection(scope = SyncScope.TRACKS, trackIds = setOf("trk-1"))
        val missingFile = verified(trackA).single().copy(
            physicallyVerified = false,
            complete = false,
        )

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server A",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA),
            localTrackEvidence = listOf(missingFile),
        )

        val item = plan.items.single()
        assertEquals(SyncItemAction.DOWNLOAD, item.action)
        assertEquals(SyncIdentityVerdict.REFERENCE_MATCH, item.identity)
    }

    @Test
    fun checksumMismatch_downloadsInsteadOfSkipping() {
        val selection = SyncSelection(scope = SyncScope.TRACKS, trackIds = setOf("trk-1"))
        val mismatch = verified(trackA).single().copy(checksum = "9".repeat(64))

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server A",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA),
            localTrackEvidence = listOf(mismatch),
        )

        val item = plan.items.single()
        assertEquals(SyncItemAction.DOWNLOAD, item.action)
        assertEquals(SyncIdentityVerdict.CONTENT_MISMATCH, item.identity)
    }

    @Test
    fun managedVerifiedCopyWithoutManifestChecksum_isReusedAsReferenceMatch() {
        val selection = SyncSelection(scope = SyncScope.TRACKS, trackIds = setOf("trk-1"))
        val noChecksum = verified(trackA).single().copy(checksum = "")

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server A",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA.copy(checksum = "")),
            localTrackEvidence = listOf(noChecksum),
        )

        val item = plan.items.single()
        assertEquals(SyncItemAction.SKIP_ALREADY_EXISTS, item.action)
        assertEquals(SyncIdentityVerdict.REFERENCE_MATCH, item.identity)
    }

    @Test
    fun sameSizeWithoutManagedVerification_isOnlySizeCompatibleAndDownloads() {
        val selection = SyncSelection(scope = SyncScope.TRACKS, trackIds = setOf("trk-1"))
        val sizeOnly = verified(trackA).single().copy(
            checksum = "",
            checksumVerified = false,
            checksumAlgorithm = "",
            managedCopyVerified = false,
        )

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server A",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA),
            localTrackEvidence = listOf(sizeOnly),
        )

        val item = plan.items.single()
        assertEquals(SyncItemAction.DOWNLOAD, item.action)
        assertEquals(SyncIdentityVerdict.SIZE_COMPATIBLE, item.identity)
    }

    @Test
    fun checksumWithIncompatibleSemantics_isNotTreatedAsVerified() {
        val selection = SyncSelection(scope = SyncScope.TRACKS, trackIds = setOf("trk-1"))
        val foreignChecksum = verified(trackA).single().copy(
            checksum = "d41d8cd98f00b204e9800998ecf8427e",
            checksumVerified = true,
            checksumAlgorithm = "md5",
            managedCopyVerified = false,
        )

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server A",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA),
            localTrackEvidence = listOf(foreignChecksum),
        )

        val item = plan.items.single()
        assertEquals(SyncItemAction.DOWNLOAD, item.action)
        assertTrue(
            item.identity == SyncIdentityVerdict.SIZE_COMPATIBLE ||
                item.identity == SyncIdentityVerdict.REFERENCE_MATCH,
        )
    }

    @Test
    fun sameContentUnderTwoLogicalReferences_keepsBothReferences() {
        val selection = SyncSelection(scope = SyncScope.FULL_LIBRARY)
        val alias = trackA.copy(trackId = "trk-alias", title = "Track One (Edit)")

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server A",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA, alias),
        )

        assertEquals(2, plan.totalSelectedTracks)
        assertEquals(setOf("trk-1", "trk-alias"), plan.items.map { it.trackId }.toSet())
    }

    @Test
    fun physicalSizeMismatch_downloadsAndReportsContentMismatch() {
        val selection = SyncSelection(scope = SyncScope.TRACKS, trackIds = setOf("trk-1"))
        val wrongSize = verified(trackA).single().copy(sizeBytes = 9 * oneMb)

        val plan = service.buildPlan(
            endpointId = "ep-1",
            endpointName = "Micro Server A",
            selection = selection,
            deliveryChoice = DeliveryChoice(),
            availableStorageBytes = 1000 * oneMb,
            candidateTracks = listOf(trackA),
            localTrackEvidence = listOf(wrongSize),
        )

        val item = plan.items.single()
        assertEquals(SyncItemAction.DOWNLOAD, item.action)
        assertEquals(SyncIdentityVerdict.CONTENT_MISMATCH, item.identity)
    }
}
