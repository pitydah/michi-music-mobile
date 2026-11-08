package org.michimusic.data.repository

/** How much of the local device catalog a successful query actually covers. */
enum class MediaCoverage {
    FULL,
    SAF_ONLY_PERMISSION_DENIED,
    SAF_ONLY_QUERY_FAILED,
}

sealed interface MediaResult<out T> {
    data class Success<out T>(
        val data: T,
        val coverage: MediaCoverage = MediaCoverage.FULL,
    ) : MediaResult<T>
    object PermissionDenied : MediaResult<Nothing>
    data class QueryFailed(val cause: Throwable) : MediaResult<Nothing>
}
