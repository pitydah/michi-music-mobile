package org.michimusic.data.cache

/** Exact selection identity. Empty sourceId is a legitimate local library. */
data class MetadataPeopleDisplayTarget(
    val sourceId: String,
    val trackId: String,
    val uri: String,
    val size: Long,
    val local: Boolean,
)

/** Not source-certified for current playback: data were indexed after an earlier
 * postcheck, but the document has NOT been reopened/rehashed by this view. */
data class MetadataPeopleIndexLabel(
    val artists: List<String>,
    val albumArtists: List<String>,
) {
    val artistsText: String get() = artists.joinToString(" · ")
    val albumArtistsText: String get() = albumArtists.joinToString(" · ")
    val sourceRevalidatedNow: Boolean get() = false
}

/** Fail closed across owner, path, size, index/sidecar and value boundaries.
 * Never split historical scalars or infer absence from a missing sidecar. */
object MetadataPeoplePresentationPolicy {
    fun forTarget(
        row: MetadataPeopleIndexedRow?, target: MetadataPeopleDisplayTarget,
    ): MetadataPeopleIndexLabel? {
        if (row == null || !target.local || !target.uri.startsWith("content://") ||
            target.trackId.isBlank() || target.size <= 0L) return null
        val evidence = row.evidence
        if (evidence.sourceId != target.sourceId || evidence.trackId != target.trackId ||
            row.indexedPath != target.uri || row.indexedSize != target.size ||
            evidence.sizeBytes != target.size || !evidence.structurallyValid()) return null
        val artists = evidence.artists() ?: return null
        val albumArtists = evidence.albumArtists() ?: return null
        if (row.indexedArtist != artists.firstOrNull().orEmpty() ||
            row.indexedAlbumArtist != albumArtists.firstOrNull().orEmpty() ||
            artists.size <= 1 && albumArtists.size <= 1) return null
        return MetadataPeopleIndexLabel(artists.toList(), albumArtists.toList())
    }

    /** All tracks in a shown album must agree; partial evidence never replaces
     * the canonical album header with the first indexed track's attribution. */
    fun commonAlbumArtists(
        ids: List<String>, labels: Map<String, MetadataPeopleIndexLabel>,
    ): String? {
        if (ids.isEmpty() || ids.distinct().size != ids.size ||
            labels.keys.intersect(ids.toSet()).size != ids.size) return null
        val first = labels[ids.first()]?.albumArtists?.takeIf { it.isNotEmpty() } ?: return null
        if (!ids.all { labels[it]?.albumArtists == first }) return null
        return first.joinToString(" · ")
    }
}
