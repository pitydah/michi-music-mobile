package org.michimusic.mobile.artist.artwork

/** Pure startup admission. Database creation for a user who never opened artist
 * enrichment is unnecessary; an empty journal must not start WorkManager work. */
internal enum class ArtistCleanupStartupAction { SKIP, ENQUEUE }

internal fun artistCleanupStartupAction(databaseExists: Boolean, pendingRows: Int): ArtistCleanupStartupAction =
    if (databaseExists && pendingRows > 0) ArtistCleanupStartupAction.ENQUEUE
    else ArtistCleanupStartupAction.SKIP
