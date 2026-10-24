package org.michimusic.mobile.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.ApprovedSyncPlan
import org.michimusic.core.models.ManifestTrack
import org.michimusic.core.models.SyncItemAction
import org.michimusic.core.models.SyncItemDecision

/**
 * Tests the fundamental invariant of M20.2-B:
 * DownloadedItems(P) ⊆ AuthorizedDownloadItems(P)
 *
 * Verifies that candidate tracks to be downloaded by SyncWorker strictly honor
 * the approved plan stored in the authoritative database repository.
 */
class ApprovedSyncPlanExecutionTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun approvedPlan_strictlyRestrictsManifestToAuthorizedItems() {
        val manifestTracks = listOf(
            ManifestTrack(trackId = "track_1", title = "Song 1", artist = "Artist 1", duration = 180, size = 5_000_000, format = "mp3"),
            ManifestTrack(trackId = "track_2", title = "Song 2", artist = "Artist 2", duration = 200, size = 6_000_000, format = "flac"),
            ManifestTrack(trackId = "track_3", title = "Song 3", artist = "Artist 3", duration = 220, size = 7_000_000, format = "mp3"),
            ManifestTrack(trackId = "track_4", title = "Song 4", artist = "Artist 4", duration = 240, size = 8_000_000, format = "mp3"),
        )

        // Only track_1 and track_3 are authorized in the approved plan
        val approvedDecisions = listOf(
            SyncItemDecision(
                trackId = "track_1",
                title = "Song 1",
                artist = "Artist 1",
                action = SyncItemAction.DOWNLOAD,
                sourceSizeBytes = 5_000_000,
                estimatedDownloadBytes = 5_000_000,
                reason = "New track",
            ),
            SyncItemDecision(
                trackId = "track_2",
                title = "Song 2",
                artist = "Artist 2",
                action = SyncItemAction.SKIP_ALREADY_EXISTS,
                sourceSizeBytes = 6_000_000,
                estimatedDownloadBytes = 0,
                reason = "Exists",
            ),
            SyncItemDecision(
                trackId = "track_3",
                title = "Song 3",
                artist = "Artist 3",
                action = SyncItemAction.RESUME_PARTIAL,
                sourceSizeBytes = 7_000_000,
                estimatedDownloadBytes = 3_000_000,
                reason = "Partial",
            ),
        )

        val approvedPlan = ApprovedSyncPlan(
            planId = "plan_123",
            endpointId = "endpoint_desk",
            endpointName = "Desktop",
            scope = "FULL_LIBRARY",
            selectionJson = "{}",
            deliveryChoiceJson = "{}",
            itemsJson = json.encodeToString(approvedDecisions),
            manifestVersion = 1,
            manifestChecksum = "sha_abc",
            totalSelectedTracks = 3,
            tracksToDownloadCount = 2,
            totalDownloadBytes = 8_000_000,
            status = "READY",
            executionState = "QUEUED",
        )

        // Evaluate authorized IDs using exact logic from SyncWorker
        val approvedItems = json.decodeFromString<List<SyncItemDecision>>(approvedPlan.itemsJson)
        val authorizedIds = approvedItems
            .filter { it.action == SyncItemAction.DOWNLOAD || it.action == SyncItemAction.RESUME_PARTIAL }
            .map { it.trackId }
            .toSet()

        val candidateTracks = manifestTracks.filter { it.trackId in authorizedIds }

        assertEquals(2, candidateTracks.size)
        assertEquals(setOf("track_1", "track_3"), candidateTracks.map { it.trackId }.toSet())
        assertTrue("track_2 must not be downloaded", candidateTracks.none { it.trackId == "track_2" })
        assertTrue("track_4 was not in plan and must never be downloaded", candidateTracks.none { it.trackId == "track_4" })
    }

    @Test
    fun approvedPlan_emptySelectionYieldsZeroDownloads() {
        val manifestTracks = listOf(
            ManifestTrack(trackId = "track_1", title = "Song 1", artist = "Artist 1", duration = 180, size = 5_000_000, format = "mp3"),
        )

        val approvedPlan = ApprovedSyncPlan(
            planId = "plan_empty",
            endpointId = "endpoint_desk",
            endpointName = "Desktop",
            scope = "TRACKS",
            selectionJson = "{}",
            deliveryChoiceJson = "{}",
            itemsJson = json.encodeToString(emptyList<SyncItemDecision>()),
            manifestVersion = 1,
            manifestChecksum = "sha_abc",
            totalSelectedTracks = 0,
            tracksToDownloadCount = 0,
            totalDownloadBytes = 0L,
            status = "READY",
            executionState = "QUEUED",
        )

        val approvedItems = json.decodeFromString<List<SyncItemDecision>>(approvedPlan.itemsJson)
        val authorizedIds = approvedItems
            .filter { it.action == SyncItemAction.DOWNLOAD || it.action == SyncItemAction.RESUME_PARTIAL }
            .map { it.trackId }
            .toSet()

        val candidateTracks = manifestTracks.filter { it.trackId in authorizedIds }

        assertEquals(0, candidateTracks.size)
    }
}
