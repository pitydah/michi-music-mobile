package org.michimusic.data.repository

/** Query recovery policy for Android providers that reject optional GENRE. */
internal fun shouldRetryGenreProjection(
    requestsGenre: Boolean,
    result: MediaResult<*>,
): Boolean = requestsGenre && when (result) {
    is MediaResult.QueryFailed -> true
    is MediaResult.Success<*> -> result.data == null
    MediaResult.PermissionDenied -> false
}
