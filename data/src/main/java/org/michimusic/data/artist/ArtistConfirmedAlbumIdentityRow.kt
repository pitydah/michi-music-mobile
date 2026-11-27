package org.michimusic.data.artist

/**
 * D36: a read-only join of an explicitly confirmed album/credit binding, its durable
 * association and the exact identity row. No tag-derived MBID is confirmation.
 * This is a Room query projection, NOT a table and does not change schema version.
 */
data class ArtistConfirmedAlbumIdentityRow(
    val libraryId: String,
    val albumKey: String,
    val creditKey: String,
    val associationId: String,
    val evidenceType: String,
    val confirmedAtMs: Long,
    val confirmedMbid: String,
)
