package org.michimusic.mobile.artist

import java.util.Locale

/**
 * Structured MBID evidence can justify a REVIEW suggestion, never an identity/binding
 * write by itself. Evidence is from an explicitly authorized, bounded existing probe.
 * Names are used ONLY to reject inconsistent credit claims, never to infer an MBID.
 */
internal sealed interface EmbeddedArtistIdentityProposal {
    data class Candidate(val mbid: String, val supportingFiles: Int) : EmbeddedArtistIdentityProposal
    data class Blocked(val reason: Reason) : EmbeddedArtistIdentityProposal

    enum class Reason { NO_EVIDENCE, CONFLICTING_IDS, AMBIGUOUS_CREDIT, WRONG_CREDIT }
}

internal object EmbeddedArtistIdentityReview {
    fun evaluate(credit: String, review: EmbeddedArtistReview): EmbeddedArtistIdentityProposal {
        val expected = credit.trim().lowercase(Locale.ROOT)
        // A corrupt/partial probe receipt must never support a review candidate.
        if (expected.isBlank() || review.inspectedFiles !in 1..24 ||
            review.taggedFiles !in 1..review.inspectedFiles ||
            review.trackEvidence.size > review.inspectedFiles ||
            review.trackEvidence.count { row ->
                row.tags.artistMbids.isNotEmpty() || row.tags.albumArtistMbids.isNotEmpty()
            } > review.taggedFiles
        ) {
            return EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.NO_EVIDENCE)
        }
        // Aggregate and per-file receipts are the same observation in two shapes.
        // If they disagree, an unsafe writer or stale metadata can manufacture one
        // plausible MBID from a contradictory file. Never let it reach review UI.
        if (review.trackEvidence.isNotEmpty()) {
            val uris = review.trackEvidence.map { it.uri }
            if (uris.any(String::isBlank) || uris.toSet().size != uris.size) {
                return EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.NO_EVIDENCE)
            }
            fun canonicalIds(ids: Set<String>): Set<String>? {
                val normalized = ids.mapNotNull(::canonicalArtistMbid).toSet()
                return normalized.takeIf { it.size == ids.size }
            }
            val aggregateTrack = canonicalIds(review.artistMbids)
            val aggregateAlbum = canonicalIds(review.albumArtistMbids)
            val actualTrack = review.trackEvidence.flatMap { it.tags.artistMbids }.toSet()
            val actualAlbum = review.trackEvidence.flatMap { it.tags.albumArtistMbids }.toSet()
            if (aggregateTrack == null || aggregateAlbum == null ||
                aggregateTrack != canonicalIds(actualTrack) ||
                aggregateAlbum != canonicalIds(actualAlbum)) {
                return EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.CONFLICTING_IDS)
            }
            // The aggregate names and per-track names come from the SAME local
            // probe. Contradictory aggregate names must not be able to overwrite
            // on-screen credit evidence while leaving a plausible MBID untouched.
            fun canonicalNames(names: Collection<String>): Set<String> = names
                .map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank).toSet()
            val actualNames = review.trackEvidence.flatMap { it.tags.creditedNames }
            val actualAlbumNames = review.trackEvidence.flatMap { it.tags.albumArtists }
            if (canonicalNames(review.creditedNames) != canonicalNames(actualNames) ||
                canonicalNames(review.albumArtists) != canonicalNames(actualAlbumNames)) {
                return EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.WRONG_CREDIT)
            }
        }
        // Aggregate-only receipts are weaker than per-file evidence. Do not let a
        // unique suggested artist ID hide a conflicting album-artist identifier.
        // Canonicalize both sets before comparing across roles.
        if (review.trackEvidence.isEmpty()) {
            val track = review.artistMbids.mapNotNull(::canonicalArtistMbid).toSet()
            val album = review.albumArtistMbids.mapNotNull(::canonicalArtistMbid).toSet()
            if (track.size != review.artistMbids.size || album.size != review.albumArtistMbids.size ||
                track.size > 1 || album.size > 1 ||
                (track.isNotEmpty() && album.isNotEmpty() && track != album)
            ) return EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.CONFLICTING_IDS)
            // Aggregate evidence has no per-file role binding. Require an
            // explicit matching artist-credit name; a unique MBID beside a
            // different name must not suggest an unrelated artist.
            val names = review.creditedNames.map { it.trim().lowercase(Locale.ROOT) }.distinct()
            if (names != listOf(expected)) {
                return EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.WRONG_CREDIT)
            }
            val proposed = review.suggestedMbid?.let(::canonicalArtistMbid)
            if (proposed != null && proposed !in track) {
                return EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.CONFLICTING_IDS)
            }
        } else if (review.artistMbids.size > 1) {
            return EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.CONFLICTING_IDS)
        }
        val mbids = linkedSetOf<String>()
        var supporting = 0
        var mismatches = 0
        for (file in review.trackEvidence) {
            val tags = file.tags
            val credited = tags.creditedNames.map { it.trim().lowercase(Locale.ROOT) }
            val albumCredited = tags.albumArtists.map { it.trim().lowercase(Locale.ROOT) }
            // A single MBID beside multiple credited names cannot identify which
            // performer that ID belongs to. Never split '&', 'feat' or display text.
            if (credited.size > 1 && tags.artistMbids.isNotEmpty()) {
                return EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.AMBIGUOUS_CREDIT)
            }
            val explicitTrackMatch = expected in credited
            val albumMatch = expected in albumCredited
            if (explicitTrackMatch && albumMatch && tags.artistMbids.isNotEmpty() &&
                tags.albumArtistMbids.isNotEmpty() && tags.artistMbids != tags.albumArtistMbids
            ) return EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.AMBIGUOUS_CREDIT)
            val ids = when {
                tags.artistMbids.isNotEmpty() && explicitTrackMatch -> tags.artistMbids
                tags.albumArtistMbids.isNotEmpty() && albumMatch -> tags.albumArtistMbids
                // Missing credit names only permits a candidate for explicit review;
                // it is never authority to bind the track artist to a guessed role.
                tags.artistMbids.isNotEmpty() && credited.isEmpty() -> tags.artistMbids
                else -> emptySet()
            }
            if (ids.isEmpty()) {
                if (tags.artistMbids.isNotEmpty() || tags.albumArtistMbids.isNotEmpty()) mismatches++
                continue
            }
            if (ids.size != 1) {
                return EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.CONFLICTING_IDS)
            }
            val candidate = canonicalArtistMbid(ids.single())
                ?: return EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.CONFLICTING_IDS)
            mbids += candidate
            supporting++
        }
        if (review.trackEvidence.isEmpty()) {
            // Older test/probe integrations expose aggregate values only. These are
            // suggestions for the explicit button; no background binding may use them.
            review.suggestedMbid?.let { canonicalArtistMbid(it) }?.let { mbids += it; supporting = 1 }
        }
        return when {
            mbids.size > 1 -> EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.CONFLICTING_IDS)
            // A single plausible MBID cannot override another inspected file whose
            // explicit credit contradicts it. Reject the entire proposal for review.
            mismatches > 0 -> EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.WRONG_CREDIT)
            mbids.size != 1 -> EmbeddedArtistIdentityProposal.Blocked(EmbeddedArtistIdentityProposal.Reason.NO_EVIDENCE)
            else -> EmbeddedArtistIdentityProposal.Candidate(mbids.single(), supporting)
        }
    }
}
