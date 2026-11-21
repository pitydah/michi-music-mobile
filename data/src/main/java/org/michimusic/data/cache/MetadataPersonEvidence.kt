package org.michimusic.data.cache

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * ME-11K: only a verified, source-owned post-write receipt may populate this
 * optional metadata sidecar. Its ordered NUL-delimited lists are never obtained
 * by splitting scalar artist strings or from a filename. Source SHA is the
 * content of the verified ORIGINAL URI after a separately certified commit.
 * Empty/legacy rows MUST NOT be backfilled by guessing metadata.
 */
@Entity(
    tableName = "metadata_people_evidence",
    primaryKeys = ["sourceId", "trackId"],
    foreignKeys = [ForeignKey(
        entity = CachedTrack::class,
        parentColumns = ["sourceId", "id"],
        childColumns = ["sourceId", "trackId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class MetadataPersonEvidence(
    val sourceId: String,
    val trackId: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val sourceSha256: String,
    val artistCount: Int,
    val artistValues: String,
    val albumArtistCount: Int,
    val albumArtistValues: String,
    val receiptVersion: Int = 1,
) {
    fun artists(): List<String>? = unpack(artistValues, artistCount)
    fun albumArtists(): List<String>? = unpack(albumArtistValues, albumArtistCount)

    /** Only validated for display; do not use as an Android source-write grant. */
    fun structurallyValid(): Boolean =
        // An empty owner is the legitimate local-library identity.
        trackId.isNotBlank() && sizeBytes > 0L && modifiedAt > 0L &&
        sourceSha256.matches(Regex("[0-9a-f]{64}")) && receiptVersion == 1 &&
        artists() != null && albumArtists() != null &&
        (artistCount > 1 || albumArtistCount > 1)

    private fun unpack(raw: String, count: Int): List<String>? {
        if (count !in 0..32) return null
        if (count == 0) return if (raw.isEmpty()) emptyList() else null
        val values = raw.split('\u0000')
        if (values.size != count || values.any {
                it.isBlank() || it.length > 2048 || it.any(Char::isSurrogate)
            }) return null
        return values
    }
}
