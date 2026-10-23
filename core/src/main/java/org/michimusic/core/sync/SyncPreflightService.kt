package org.michimusic.core.sync

import org.michimusic.core.models.DeliveryChoice
import org.michimusic.core.models.IssueSeverity
import org.michimusic.core.models.ManifestTrack
import org.michimusic.core.models.SyncItemAction
import org.michimusic.core.models.SyncItemDecision
import org.michimusic.core.models.SyncPlan
import org.michimusic.core.models.SyncPreflightIssue
import org.michimusic.core.models.SyncPreflightStatus
import org.michimusic.core.models.SyncScope
import org.michimusic.core.models.SyncSelection
import java.util.UUID

class SyncPreflightService {

    /**
     * Executes the intelligent domain preflight for a sync operation.
     * Evaluates selection, catalog resolution, deduplication, existing local library matches,
     * partial checkpoints, storage boundaries, and produces an authoritative SyncPlan.
     */
    fun buildPlan(
        endpointId: String,
        endpointName: String,
        selection: SyncSelection,
        deliveryChoice: DeliveryChoice,
        availableStorageBytes: Long,
        candidateTracks: List<ManifestTrack>,
        localTrackFingerprints: Set<String> = emptySet(),
        existingPartials: Map<String, Long> = emptyMap(),
    ): SyncPlan {
        val planId = UUID.randomUUID().toString()

        if (selection.isEmpty) {
            val issue = SyncPreflightIssue(
                code = "EMPTY_SELECTION",
                message = "No se ha seleccionado ninguna pista, álbum, artista o playlist para sincronizar.",
                severity = IssueSeverity.BLOCKING,
            )
            return SyncPlan(
                planId = planId,
                endpointId = endpointId,
                endpointName = endpointName,
                selection = selection,
                deliveryChoice = deliveryChoice,
                items = emptyList(),
                totalSelectedTracks = 0,
                tracksToDownloadCount = 0,
                tracksSkippedCount = 0,
                tracksToResumeCount = 0,
                totalDownloadBytes = 0L,
                availableStorageBytes = availableStorageBytes,
                safetyBufferBytes = StoragePreflightEvaluator.SAFETY_BUFFER_BYTES,
                remainingStorageAfterSyncBytes = availableStorageBytes,
                issues = listOf(issue),
                status = SyncPreflightStatus.BLOCKED_EMPTY_SELECTION,
            )
        }

        // 1. Filter candidate tracks according to scope and selection IDs
        val filteredTracks = when (selection.scope) {
            SyncScope.FULL_LIBRARY -> candidateTracks
            SyncScope.PLAYLISTS -> {
                // If playlist IDs are specified, filter by them
                candidateTracks.filter { it.trackId in selection.trackIds || selection.playlistIds.isNotEmpty() }
            }
            SyncScope.ALBUMS -> candidateTracks.filter { it.album in selection.albumIds }
            SyncScope.ARTISTS -> candidateTracks.filter { it.artist in selection.artistIds }
            SyncScope.TRACKS -> candidateTracks.filter { it.trackId in selection.trackIds }
        }

        // 2. Deduplicate tracks by trackId
        val dedupedTracks = filteredTracks.distinctBy { it.trackId }

        // 3. Classify each track into an action decision
        val decisions = dedupedTracks.map { track ->
            val trackFingerprint = track.checksum.ifEmpty { "${track.title.trim().lowercase()}_${track.artist.trim().lowercase()}_${track.duration}" }
            val alreadyLocal = localTrackFingerprints.contains(track.trackId) || localTrackFingerprints.contains(trackFingerprint)

            if (alreadyLocal) {
                SyncItemDecision(
                    trackId = track.trackId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    action = SyncItemAction.SKIP_ALREADY_EXISTS,
                    sourceSizeBytes = track.size,
                    estimatedDownloadBytes = 0L,
                    reason = "Ya existe en la biblioteca local",
                )
            } else {
                val partialBytes = existingPartials[track.trackId]
                if (partialBytes != null && partialBytes > 0 && partialBytes < track.size) {
                    val remainingBytes = (track.size - partialBytes).coerceAtLeast(0L)
                    SyncItemDecision(
                        trackId = track.trackId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        action = SyncItemAction.RESUME_PARTIAL,
                        sourceSizeBytes = track.size,
                        estimatedDownloadBytes = remainingBytes,
                        reason = "Reanudando descarga (${StoragePreflightEvaluator.formatBytes(partialBytes)} existentes)",
                    )
                } else {
                    SyncItemDecision(
                        trackId = track.trackId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        action = SyncItemAction.DOWNLOAD,
                        sourceSizeBytes = track.size,
                        estimatedDownloadBytes = track.size,
                        reason = "Nueva pista a sincronizar",
                    )
                }
            }
        }

        // 4. Evaluate storage requirements against available disk space
        val storageEval = StoragePreflightEvaluator.evaluate(availableStorageBytes, decisions)

        val tracksToDownload = decisions.count { it.action == SyncItemAction.DOWNLOAD || it.action == SyncItemAction.REPLACE_BETTER_QUALITY }
        val tracksSkipped = decisions.count { it.action == SyncItemAction.SKIP_ALREADY_EXISTS }
        val tracksToResume = decisions.count { it.action == SyncItemAction.RESUME_PARTIAL }

        val issues = storageEval.issues.toMutableList()
        var status = storageEval.status

        if (decisions.isNotEmpty() && tracksToDownload == 0 && tracksToResume == 0) {
            issues.add(
                SyncPreflightIssue(
                    code = "ALL_TRACKS_ALREADY_SYNCED",
                    message = "Todas las pistas seleccionadas ya están disponibles en este dispositivo.",
                    severity = IssueSeverity.WARNING,
                )
            )
        }

        return SyncPlan(
            planId = planId,
            endpointId = endpointId,
            endpointName = endpointName,
            selection = selection,
            deliveryChoice = deliveryChoice,
            items = decisions,
            totalSelectedTracks = decisions.size,
            tracksToDownloadCount = tracksToDownload,
            tracksSkippedCount = tracksSkipped,
            tracksToResumeCount = tracksToResume,
            totalDownloadBytes = storageEval.totalDownloadBytes,
            availableStorageBytes = availableStorageBytes,
            safetyBufferBytes = storageEval.safetyBufferBytes,
            remainingStorageAfterSyncBytes = storageEval.remainingAfterSyncBytes,
            issues = issues,
            status = status,
        )
    }
}
