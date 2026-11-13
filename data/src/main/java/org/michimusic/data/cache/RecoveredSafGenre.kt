package org.michimusic.data.cache

/** Checked against path and size before updating a SAF row; no audio file is changed. */
data class RecoveredSafGenre(
    val sourceId: String,
    val trackId: String,
    val filepath: String,
    val size: Long,
    val genre: String,
)
