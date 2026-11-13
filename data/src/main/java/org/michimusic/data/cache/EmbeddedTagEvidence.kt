package org.michimusic.data.cache

import androidx.room.Entity

/**
 * Metadata evidence is scoped by (sourceId, trackId), never by a globally ambiguous ID.
 * No filename or filesystem path is persisted. Unavailable values are empty, not invented.
 * Genre values separated by NUL originate only in structured tags; never split user text on '/'.
 */
@Entity(tableName = "embedded_tag_evidence", primaryKeys = ["sourceId", "trackId"])
data class EmbeddedTagEvidence(
    val sourceId: String,
    val trackId: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val parserVersion: Int,
    val genre: String = "",
    val genreValues: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val composer: String = "",
    val trackNumber: Int = 0,
    val trackTotal: Int = 0,
    val discNumber: Int = 0,
    val discTotal: Int = 0,
    val date: String = "",
) {
    companion object {
        /** Increment if extraction semantics change; old rows must be reevaluated. */
        const val CURRENT_PARSER_VERSION = 3
    }

    /** MediaStore can reuse evidence only if both fingerprints are authoritative. */
    fun matchesMediaStore(size: Long, modifiedSeconds: Long): Boolean =
        modifiedSeconds > 0 && size >= 0 && sizeBytes == size &&
            modifiedAt == modifiedSeconds && parserVersion == CURRENT_PARSER_VERSION
}
