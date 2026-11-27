package org.michimusic.mobile.artist

/**
 * D37: optional presentation-only partition for confirmed same-literal homonyms.
 * Every member needs the exact persisted MBID read by D36. An ambiguous album,
 * a missing proof, or a duplicated ownership returns null (the original locked tile).
 * This is NEVER a user identity selection, artwork permission or metadata write.
 */
internal object ArtistHomonymMosaicPolicy {
    fun split(
        aggregate: ArtistPresentationEntry,
        occurrences: List<ArtistMembershipOccurrence>,
    ): List<ArtistPresentationEntry>? {
        val library = aggregate.libraryId ?: return null
        if (library.isBlank() || library == "SCOPE_ALL" || aggregate.mergedCredits != listOf(aggregate.credit) ||
            aggregate.identityPartitioned || occurrences.size > 2_048 || occurrences.isEmpty()
        ) return null
        val review = ArtistLiteralHomonymEvidence.inspect(aggregate.credit, library, occurrences)
            as? ArtistLiteralHomonymResult.Review ?: return null
        if (review.unresolved.isNotEmpty() || review.buckets.size < 2 || review.buckets.size > 16) return null
        // A shared album can split ONLY when every member of that album carries
        // an explicit track confirmation in Room, not merely a tag or album MBID.
        val ownerByAlbum = mutableMapOf<String, String>()
        for (bucket in review.buckets) for (albumId in bucket.albumIds) {
            val previous = ownerByAlbum.putIfAbsent(albumId, bucket.mbid)
            if (previous != null && previous != bucket.mbid) {
                val sameAlbum = occurrences.filter { it.albumId == albumId }
                if (sameAlbum.isEmpty() || sameAlbum.any { !it.exactTrackConfirmed } ||
                    sameAlbum.map { it.trackIdentity }.distinct().size != sameAlbum.size) return null
            }
        }
        if (review.buckets.sumOf { it.memberCount } != occurrences.size ||
            ownerByAlbum.keys != aggregate.albumIds.toSet() ||
            occurrences.map { it.trackIdentity }.toSet().size != occurrences.size
        ) return null
        return review.buckets.map { bucket ->
            aggregate.copy(
                albumIds = bucket.albumIds,
                trackCount = bucket.memberCount,
                mbid = bucket.mbid,
                identityPartitioned = true,
                presentationKey = "homonym:${bucket.mbid}:${aggregate.credit}",
            )
        }
    }
}
