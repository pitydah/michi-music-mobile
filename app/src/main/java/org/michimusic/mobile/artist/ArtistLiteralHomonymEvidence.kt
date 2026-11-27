package org.michimusic.mobile.artist

/**
 * D35: local evidence-only partitions for performers who share the SAME literal artist credit.
 * A bucket is not a durable identity link, a mosaic card or permission to edit a profile.
 * Members retain album and track identity; no name, photograph or album title is authority.
 */
internal data class ArtistLiteralHomonymBucket(
    val credit: String,
    val libraryId: String,
    val mbid: String,
    val members: List<ArtistMembershipOccurrence>,
) {
    val albumIds: List<String> get() = members.map { it.albumId }.distinct().sorted()
    val memberCount: Int get() = members.size
}

internal sealed interface ArtistLiteralHomonymResult {
    data class Review(
        val buckets: List<ArtistLiteralHomonymBucket>,
        val unresolved: List<ArtistMembershipOccurrence>,
    ) : ArtistLiteralHomonymResult {
        /** A group always requires explicit review; it is never an association write. */
        val needsReview: Boolean get() = true
    }
    data object NotConflicting : ArtistLiteralHomonymResult
    data class Blocked(val reason: Reason) : ArtistLiteralHomonymResult

    enum class Reason {
        NO_EXACT_LIBRARY, MIXED_SCOPE_OR_CREDIT, INVALID_OCCURRENCE,
        DUPLICATE_OCCURRENCE, INVALID_MUSICBRAINZ_ID, EXCEEDS_LOCAL_BUDGET,
    }
}

/** Pure, bounded calculation. Callers must provide already-observed per-MEMBER MBIDs. */
internal object ArtistLiteralHomonymEvidence {
    private const val MAX_MEMBERS = 2_048

    fun inspect(
        literalCredit: String,
        libraryId: String?,
        observations: List<ArtistMembershipOccurrence>,
    ): ArtistLiteralHomonymResult {
        if (libraryId.isNullOrBlank())
            return ArtistLiteralHomonymResult.Blocked(ArtistLiteralHomonymResult.Reason.NO_EXACT_LIBRARY)
        val credit = literalCredit.trim()
        if (credit.isBlank() || observations.isEmpty() || observations.size > MAX_MEMBERS)
            return ArtistLiteralHomonymResult.Blocked(ArtistLiteralHomonymResult.Reason.EXCEEDS_LOCAL_BUDGET)
        val known = linkedMapOf<String, MutableList<ArtistMembershipOccurrence>>()
        val unresolved = mutableListOf<ArtistMembershipOccurrence>()
        val visited = hashSetOf<Pair<String, String>>()
        for (observation in observations) {
            if (observation.libraryId != libraryId || observation.literalCredit.trim() != credit)
                return ArtistLiteralHomonymResult.Blocked(ArtistLiteralHomonymResult.Reason.MIXED_SCOPE_OR_CREDIT)
            if (observation.albumId.isBlank() || observation.trackIdentity.isBlank())
                return ArtistLiteralHomonymResult.Blocked(ArtistLiteralHomonymResult.Reason.INVALID_OCCURRENCE)
            if (!visited.add(observation.albumId to observation.trackIdentity))
                return ArtistLiteralHomonymResult.Blocked(ArtistLiteralHomonymResult.Reason.DUPLICATE_OCCURRENCE)
            val rawMbid = observation.verifiedMbid
            if (rawMbid == null) {
                unresolved += observation
                continue
            }
            val normalized = canonicalArtistMbid(rawMbid)
                ?: return ArtistLiteralHomonymResult.Blocked(
                    ArtistLiteralHomonymResult.Reason.INVALID_MUSICBRAINZ_ID,
                )
            known.getOrPut(normalized) { mutableListOf() } += observation.copy(verifiedMbid = normalized)
        }
        if (known.size < 2) return ArtistLiteralHomonymResult.NotConflicting
        return ArtistLiteralHomonymResult.Review(
            buckets = known.entries.sortedBy { it.key }.map { (mbid, rows) ->
                ArtistLiteralHomonymBucket(
                    credit = credit, libraryId = libraryId, mbid = mbid,
                    members = rows.sortedWith(compareBy({ it.albumId }, { it.trackIdentity })),
                )
            },
            unresolved = unresolved.sortedWith(compareBy({ it.albumId }, { it.trackIdentity })),
        )
    }
}
