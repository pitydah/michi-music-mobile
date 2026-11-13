package org.michimusic.data.cache

/** Minimal Room projection for UI7. Never use an ID without its source owner. */
data class CachedGenreMetadata(
    val sourceId: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val filepath: String,
    val genre: String,
    /** NUL-separated values from a matching source-owned embedded tag sidecar. */
    val genreValues: String = "",
)
