package org.michimusic.mobile.ui.components

import kotlinx.coroutines.CancellationException
import org.michimusic.core.models.Track
import org.michimusic.data.repository.ExtendedTagEvidenceResult
import org.michimusic.data.repository.ExtendedTagUnavailableReason
import org.michimusic.data.repository.ExtendedTrackMetadata
import org.michimusic.data.repository.ExtendedTrackMetadataRepository

/**
 * K48-S4: the one evidence contract shared by both genre routes.
 *
 * Now Playing (through [TrackExtendedMetadataDetails]) and the Genres route
 * (through [MichiGenreNormalizationHost]) describe the same file, so they must
 * reach the same verdict with the same reason. Neither route may infer "no
 * genre" from a failed read: [Unavailable] always means the tag could not be
 * verified, and the editor stays read-only.
 */
internal sealed interface GenreEvidenceUiState {
    data object Loading : GenreEvidenceUiState

    data class Verified(val metadata: ExtendedTrackMetadata) : GenreEvidenceUiState {
        val genres: List<String> get() = metadata.genres
    }

    data class Unavailable(val reason: ExtendedTagUnavailableReason?) : GenreEvidenceUiState
}

/**
 * Authoritative, user-triggered read. [track.size] belongs to the receipt: the
 * same URI with different bytes is a different document and cannot inherit the
 * previous evidence.
 */
internal suspend fun loadGenreEvidence(
    repository: ExtendedTrackMetadataRepository,
    track: Track,
): GenreEvidenceUiState {
    val result = try {
        repository.loadVerifiedResult(track.sourceId, track.id, track.filepath, track.size)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return GenreEvidenceUiState.Unavailable(ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE)
    }
    return when (result) {
        is ExtendedTagEvidenceResult.Verified ->
            if (result.metadata.sourceId != track.sourceId || result.metadata.trackId != track.id) {
                GenreEvidenceUiState.Unavailable(ExtendedTagUnavailableReason.TAG_MISMATCH)
            } else {
                GenreEvidenceUiState.Verified(result.metadata)
            }
        is ExtendedTagEvidenceResult.Unavailable ->
            GenreEvidenceUiState.Unavailable(result.reason)
    }
}
