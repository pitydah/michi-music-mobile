package org.michimusic.data.repository

sealed interface MediaResult<out T> {
    data class Success<out T>(val data: T) : MediaResult<T>
    object PermissionDenied : MediaResult<Nothing>
    data class QueryFailed(val cause: Throwable) : MediaResult<Nothing>
}
