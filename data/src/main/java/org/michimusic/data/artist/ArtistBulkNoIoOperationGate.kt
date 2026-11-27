package org.michimusic.data.artist

/** D43 S4: explicit capability boundary for the entire prepared bulk workflow.
 * The D24 durable consent has NOT authorized HTTP dispatch. A typed operation is
 * either local inspection/reconciliation or a prohibited side effect. */
enum class ArtistBulkNoIoOperation {
    INSPECT_LOCAL, REVIEW_PERMISSION, RESERVE_NO_IO, RELEASE_NO_IO,
    CLOSE_TERMINAL_NO_IO, RECONCILE_STALE_NO_IO,
    HTTP_DISPATCH, SCHEDULE_PROVIDER_WORK, WRITE_ARTIST_PROFILE,
    REPLACE_MANUAL_PHOTO, MUTATE_AUDIO_METADATA,
}

object ArtistBulkNoIoOperationGate {
    private val localOnly = setOf(
        ArtistBulkNoIoOperation.INSPECT_LOCAL,
        ArtistBulkNoIoOperation.REVIEW_PERMISSION,
        ArtistBulkNoIoOperation.RESERVE_NO_IO,
        ArtistBulkNoIoOperation.RELEASE_NO_IO,
        ArtistBulkNoIoOperation.CLOSE_TERMINAL_NO_IO,
        ArtistBulkNoIoOperation.RECONCILE_STALE_NO_IO,
    )

    fun permits(operation: ArtistBulkNoIoOperation): Boolean = operation in localOnly
    fun mayContactProvider(): Boolean = false
}
