package org.michimusic.mobile.ui.screens

/**
 * Real states of the artist detail collection. The plan (M2) fixes this precedence:
 * membership/Room error > incomplete scope read > registered collection available
 * (READY even when MediaStore fails) > MediaStore loading > MediaStore error without
 * albums > genuinely empty collection.
 *
 * The patch shipped `ArtistDetailScreen` using these symbols but omitted this file, so
 * it never compiled. Keeping the policy pure lets the five states be tested on the JVM
 * without Room, MediaStore or Compose.
 */
enum class ArtistDetailCollectionPhase {
    LOADING,
    SOURCE_ERROR,
    MEDIA_ERROR,
    EMPTY,
    READY,
}

fun artistDetailCollectionPhase(
    scopeReady: Boolean,
    scopeError: String?,
    mediaLoading: Boolean,
    mediaError: String?,
    hasAlbums: Boolean,
): ArtistDetailCollectionPhase = when {
    // A failing membership/Room read is reported even if some albums are cached: the
    // collection may be missing libraries, so it is not trustworthy as READY.
    scopeError != null -> ArtistDetailCollectionPhase.SOURCE_ERROR
    // The scope itself was not resolved yet.
    !scopeReady -> ArtistDetailCollectionPhase.LOADING
    // Registered catalog data wins over a MediaStore failure: the artist and its albums
    // stay visible with a warning instead of being replaced by an error screen.
    hasAlbums -> ArtistDetailCollectionPhase.READY
    mediaLoading -> ArtistDetailCollectionPhase.LOADING
    // A real MediaStore failure with no registered albums is an error, never EMPTY.
    mediaError != null -> ArtistDetailCollectionPhase.MEDIA_ERROR
    else -> ArtistDetailCollectionPhase.EMPTY
}
