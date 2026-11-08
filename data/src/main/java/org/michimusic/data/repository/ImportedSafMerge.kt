package org.michimusic.data.repository

import org.michimusic.core.models.Track

/** A document grant can be narrower than broad READ_MEDIA_AUDIO permission. */
internal fun mergeImportedSafTracks(
    mediaStore: MediaResult<List<Track>>,
    imported: List<Track>,
): MediaResult<List<Track>> = when (mediaStore) {
    is MediaResult.Success -> {
        // Preserve the MediaStore entry if a provider duplicates an imported URI.
        MediaResult.Success(
            (mediaStore.data + imported).distinctBy { it.filepath.ifBlank { it.id } },
            coverage = mediaStore.coverage,
        )
    }
    MediaResult.PermissionDenied -> if (imported.isNotEmpty()) {
        MediaResult.Success(imported, MediaCoverage.SAF_ONLY_PERMISSION_DENIED)
    } else MediaResult.PermissionDenied
    is MediaResult.QueryFailed -> if (imported.isNotEmpty()) {
        MediaResult.Success(imported, MediaCoverage.SAF_ONLY_QUERY_FAILED)
    } else mediaStore
}
