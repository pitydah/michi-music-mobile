package org.michimusic.data.repository

/**
 * The source ID is already exact at the repository lookup. When a legacy peer
 * omits genre, retain the old metadata only if its track identity is unchanged.
 * A reused remote track ID must never inherit another recording's genre.
 */
internal fun sameLegacySyncTrack(
    oldTitle: String, oldArtist: String, oldAlbum: String, oldDuration: Long,
    newTitle: String, newArtist: String, newAlbum: String, newDuration: Long,
): Boolean {
    fun equivalent(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
    return oldTitle.isNotBlank() && newTitle.isNotBlank() &&
        equivalent(oldTitle, newTitle) && equivalent(oldArtist, newArtist) &&
        equivalent(oldAlbum, newAlbum) && oldDuration == newDuration
}
