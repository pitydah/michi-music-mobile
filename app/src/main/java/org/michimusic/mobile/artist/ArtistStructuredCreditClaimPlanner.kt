package org.michimusic.mobile.artist

import java.util.Locale

/** Constructs ONE explicit-review request from co-located MBIDs, never across files.
 * No network, storage reads, identity writes, or background activity.
 */
internal sealed interface StructuredCreditPlan {
    /** Source URI exists only in the UI process. Only its stable local track ID is persisted. */
    data class Ready(val claim: MusicBrainzCreditClaim, val supportingFiles: Int,
        val supportingUri: String) : StructuredCreditPlan
    data class Blocked(val reason: Reason) : StructuredCreditPlan
    enum class Reason { UNTRUSTED_TAG_RECEIPT, NO_COMPLETE_PAIR, AMBIGUOUS_ROLE, BAD_IDS }
}

internal object ArtistStructuredCreditClaimPlanner {
    fun plan(artistCredit: String, review: EmbeddedArtistReview): StructuredCreditPlan {
        val suggestion = EmbeddedArtistIdentityReview.evaluate(artistCredit, review)
        val candidate = (suggestion as? EmbeddedArtistIdentityProposal.Candidate)?.mbid
            ?: return StructuredCreditPlan.Blocked(StructuredCreditPlan.Reason.UNTRUSTED_TAG_RECEIPT)
        val expected = artistCredit.trim().lowercase(Locale.ROOT)
        // Explicitly named credit roles are stronger than nameless role overlap.
        data class Receipt(val claim: MusicBrainzCreditClaim, val uri: String, val named: Boolean)
        val claims = mutableListOf<Receipt>()
        var ambiguous = false
        var malformed = false
        for (entry in review.trackEvidence.take(24)) {
            val tags = entry.tags
            val namedTrack = tags.creditedNames.map { it.trim().lowercase(Locale.ROOT) }
            val namedAlbum = tags.albumArtists.map { it.trim().lowercase(Locale.ROOT) }
            val artistIds = tags.artistMbids.mapNotNull(::canonicalArtistMbid).toSet()
            val albumIds = tags.albumArtistMbids.mapNotNull(::canonicalArtistMbid).toSet()
            val track = candidate in artistIds && (namedTrack.isEmpty() || expected in namedTrack)
            val album = candidate in albumIds && (namedAlbum.isEmpty() || expected in namedAlbum)
            if (!track && !album) continue
            // An exact track credit takes priority only when the credit is explicitly
            // named; a nameless role overlap is not independent role evidence.
            val role = when {
                track && expected in namedTrack -> VerifiedCreditRole.TRACK_ARTIST
                album && expected in namedAlbum -> VerifiedCreditRole.ALBUM_ARTIST
                track && !album -> VerifiedCreditRole.TRACK_ARTIST
                album && !track -> VerifiedCreditRole.ALBUM_ARTIST
                else -> { ambiguous = true; continue }
            }
            val recording = tags.recordingMbids.singleOrNull()?.let(::canonicalArtistMbid)
            val release = tags.releaseMbids.singleOrNull()?.let(::canonicalArtistMbid)
            if (tags.recordingMbids.size > 1 || tags.releaseMbids.size > 1) {
                ambiguous = true; continue
            }
            if (tags.recordingMbids.isNotEmpty() && recording == null ||
                tags.releaseMbids.isNotEmpty() && release == null) {
                malformed = true; continue
            }
            if (recording != null && release != null) {
                claims += Receipt(MusicBrainzCreditClaim(candidate, recording, release, role),
                    entry.uri, named = when (role) {
                        VerifiedCreditRole.TRACK_ARTIST -> expected in namedTrack
                        VerifiedCreditRole.ALBUM_ARTIST -> expected in namedAlbum
                    })
            }
        }
        if (malformed) return StructuredCreditPlan.Blocked(StructuredCreditPlan.Reason.BAD_IDS)
        if (ambiguous) return StructuredCreditPlan.Blocked(StructuredCreditPlan.Reason.AMBIGUOUS_ROLE)
        if (claims.isEmpty()) return StructuredCreditPlan.Blocked(StructuredCreditPlan.Reason.NO_COMPLETE_PAIR)
        // A single representative explicit request is enough. It cannot assert that
        // all albums match. Another selection requires another deliberate tap.
        val grouped = claims.groupBy { it.claim }
        // Prefer at least one explicit named role before volume of nameless tags.
        // All claims remain REVIEW_ONLY; this picks one local request, not identity.
        val representativeClaim = grouped.entries.sortedWith(
            compareByDescending<Map.Entry<MusicBrainzCreditClaim, List<Receipt>>> {
                it.value.count(Receipt::named)
            }.thenByDescending { it.value.size }
                .thenBy { it.key.recordingMbid.orEmpty() }
                .thenBy { it.key.releaseMbid.orEmpty() }
                .thenBy { it.key.role.name },
        ).first().key
        val strongest = grouped.getValue(representativeClaim)
        return StructuredCreditPlan.Ready(
            representativeClaim, strongest.size,
            strongest.map(Receipt::uri).minOrNull()!!,
        )
    }
}
