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
        manifestPlaylists: List<org.michimusic.core.models.ManifestPlaylist> = emptyList(),
        localTrackFingerprints: Set<String> = emptySet(),
        existingPartials: Map<String, Long> = emptyMap(),
        storageStatus: org.michimusic.core.models.StoragePreflightStatus = org.michimusic.core.models.StoragePreflightStatus.AVAILABLE,
        manifestVersion: Int = 1,
        manifestChecksum: String = "",
    ): SyncPlan {
        val planId = UUID.randomUUID().toString()

        if (storageStatus == org.michimusic.core.models.StoragePreflightStatus.NOT_WRITABLE) {
            val issue = SyncPreflightIssue(
                code = "STORAGE_NOT_WRITABLE",
                message = "El almacenamiento de destino no tiene permisos de escritura o no está disponible.",
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
                availableStorageBytes = 0L,
                safetyBufferBytes = StoragePreflightEvaluator.SAFETY_BUFFER_BYTES,
                remainingStorageAfterSyncBytes = 0L,
                issues = listOf(issue),
                status = SyncPreflightStatus.BLOCKED_NO_STORAGE,
                manifestVersion = manifestVersion,
                manifestChecksum = manifestChecksum,
            )
        }

        if (storageStatus == org.michimusic.core.models.StoragePreflightStatus.UNKNOWN) {
            val issue = SyncPreflightIssue(
                code = "STORAGE_UNKNOWN",
                message = "No se pudo determinar el espacio de almacenamiento disponible de manera confiable.",
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
                availableStorageBytes = 0L,
                safetyBufferBytes = StoragePreflightEvaluator.SAFETY_BUFFER_BYTES,
                remainingStorageAfterSyncBytes = 0L,
                issues = listOf(issue),
                status = SyncPreflightStatus.BLOCKED_NO_STORAGE,
                manifestVersion = manifestVersion,
                manifestChecksum = manifestChecksum,
            )
        }

        val hasEmptySelection = selection.isEmpty || (selection.scope == SyncScope.FULL_LIBRARY && candidateTracks.isEmpty())

        if (hasEmptySelection) {
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
                manifestVersion = manifestVersion,
                manifestChecksum = manifestChecksum,
            )
        }

        // 1. Filter candidate tracks according to scope and selection IDs
        val filteredTracks = when (selection.scope) {
            SyncScope.FULL_LIBRARY -> candidateTracks
            SyncScope.PLAYLISTS -> {
                val selectedPlaylistTrackIds = manifestPlaylists
                    .filter { it.playlistId in selection.playlistIds }
                    .flatMap { it.trackIds }
                    .toSet()
                candidateTracks.filter { it.trackId in selectedPlaylistTrackIds || it.trackId in selection.trackIds }
            }
            SyncScope.ALBUMS -> candidateTracks.filter { it.album in selection.albumIds }
            SyncScope.ARTISTS -> candidateTracks.filter { it.artist in selection.artistIds }
            SyncScope.TRACKS -> candidateTracks.filter { it.trackId in selection.trackIds }
        }

        if (filteredTracks.isEmpty()) {
            val issue = SyncPreflightIssue(
                code = "EMPTY_SELECTION",
                message = "La selección actual no contiene pistas válidas para transferir.",
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
                manifestVersion = manifestVersion,
                manifestChecksum = manifestChecksum,
            )
        }

        // 2. Deduplicate tracks by trackId or checksum
        val dedupedTracks = filteredTracks.distinctBy {
            if (it.checksum.isNotEmpty()) it.checksum else it.trackId
        }

        // 3. Classify each track into an action decision with realistic size estimation
        val decisions = dedupedTracks.map { track ->
            val trackFingerprint = track.checksum.ifEmpty { "${track.title.trim().lowercase()}_${track.artist.trim().lowercase()}_${track.duration}" }
            val alreadyLocal = localTrackFingerprints.contains(track.trackId) || localTrackFingerprints.contains(trackFingerprint)
            val estimatedFullBytes = estimateTrackDownloadBytes(track, deliveryChoice)

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
                if (partialBytes != null && partialBytes > 0 && partialBytes < estimatedFullBytes) {
                    val remainingBytes = (estimatedFullBytes - partialBytes).coerceAtLeast(0L)
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
                        estimatedDownloadBytes = estimatedFullBytes,
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
        val status = storageEval.status

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
            manifestVersion = manifestVersion,
            manifestChecksum = manifestChecksum,
        )
    }

    private fun estimateTrackDownloadBytes(track: ManifestTrack, deliveryChoice: DeliveryChoice): Long {
        return when (deliveryChoice.profile) {
            org.michimusic.core.models.DeliveryProfile.ORIGINAL -> track.size
            org.michimusic.core.models.DeliveryProfile.BALANCED -> {
                // AAC 256 kbps ~= 32 KB/s
                if (track.duration > 0) {
                    val est = track.duration.toLong() * 32_000L
                    minOf(est, track.size)
                } else {
                    (track.size * 0.4).toLong().coerceAtLeast(1024L)
                }
            }
            org.michimusic.core.models.DeliveryProfile.LOW_STORAGE -> {
                // Opus 128 kbps ~= 16 KB/s
                if (track.duration > 0) {
                    val est = track.duration.toLong() * 16_000L
                    minOf(est, track.size)
                } else {
                    (track.size * 0.2).toLong().coerceAtLeast(1024L)
                }
            }
        }
    }
}
