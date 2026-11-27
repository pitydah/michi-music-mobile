package org.michimusic.mobile.artist.artwork

/** Inode identity before unlink. A null fileKey is unprovable, not a positive match.
 * This is a last local check; cross-process filesystem races still need Android tests. */
internal data class ArtistArtworkFileIdentity(
    val regular: Boolean,
    val key: String?,
    val bytes: Long,
    val lastModifiedEpochMs: Long,
)

internal fun sameArtworkFileForUnlink(
    before: ArtistArtworkFileIdentity,
    after: ArtistArtworkFileIdentity,
): Boolean = before.regular && after.regular && before.key != null &&
    after.key == before.key && before.bytes == after.bytes &&
    before.bytes > 0L && before.lastModifiedEpochMs == after.lastModifiedEpochMs
