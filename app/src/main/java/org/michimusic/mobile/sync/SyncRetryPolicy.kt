package org.michimusic.mobile.sync

/** WorkManager retry policy for failures not classified by the explicit LinkException path. */
internal object SyncRetryPolicy {
    fun shouldRetryUnhandled(error: Throwable, attempt: Int): Boolean =
        error is java.io.IOException && attempt in 0..2
}
