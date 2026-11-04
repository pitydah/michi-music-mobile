package org.michimusic.mobile.screens

import org.michimusic.data.repository.MediaResult

/**
 * Truthful state of the local media library. EMPTY is only valid after a successful
 * MediaStore query that returned no tracks; a denied permission or a failed query must
 * never be rendered as an empty library.
 */
enum class MediaLibraryState {
    LOADING,
    PERMISSION_DENIED,
    QUERY_ERROR,
    EMPTY,
    READY,
}

/**
 * Maps a typed media result to the only state the UI is allowed to render. Kept pure so
 * the precedence (denied/failed never become EMPTY, playlists count as content) is
 * testable on the JVM without MediaStore.
 */
fun mediaStateFor(
    result: MediaResult<*>,
    trackCount: Int,
    hasPlaylists: Boolean,
): MediaLibraryState = when (result) {
    is MediaResult.Success -> if (trackCount == 0 && !hasPlaylists) {
        MediaLibraryState.EMPTY
    } else {
        MediaLibraryState.READY
    }
    is MediaResult.PermissionDenied -> MediaLibraryState.PERMISSION_DENIED
    is MediaResult.QueryFailed -> MediaLibraryState.QUERY_ERROR
}
