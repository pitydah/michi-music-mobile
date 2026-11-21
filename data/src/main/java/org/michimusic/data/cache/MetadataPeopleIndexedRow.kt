package org.michimusic.data.cache

import androidx.room.ColumnInfo
import androidx.room.Embedded

/** One joined, owner-scoped Room observation. This is indexed evidence, NOT a
 * fresh file read or a claim that a provider has not changed since indexing. */
data class MetadataPeopleIndexedRow(
    @Embedded val evidence: MetadataPersonEvidence,
    @ColumnInfo(name = "indexedPath") val indexedPath: String,
    @ColumnInfo(name = "indexedSize") val indexedSize: Long,
    @ColumnInfo(name = "indexedArtist") val indexedArtist: String,
    @ColumnInfo(name = "indexedAlbumArtist") val indexedAlbumArtist: String,
)
