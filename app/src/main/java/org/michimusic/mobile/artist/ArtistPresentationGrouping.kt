package org.michimusic.mobile.artist

import java.util.Locale

/**
 * K48-R01 — how artist credits become presentation groups.
 *
 * Two separate concerns, deliberately kept apart:
 *
 * 1. **Membership aggregation.** Every album and every track a credit was observed on is
 *    kept. Grouping by literal credit must never drop a later album that happens to repeat
 *    an earlier credit. Three `A-ha` albums with ten tracks each are one entry with three
 *    albums and thirty tracks, never one album with ten.
 * 2. **Identity resolution.** A local credit is metadata, never proof of identity. Two
 *    credits that differ only in capitalisation, spacing or punctuation (`A-ha` / `a-ha`)
 *    are only *discovery* candidates: they share a presentation entry exclusively when a
 *    verified MusicBrainz identity backs both, and never when the identities conflict, when
 *    only one is verified, or when the credits come from different libraries.
 */
data class ArtistMembershipOccurrence(
    /** Library scope the membership was observed in; null means the aggregate/public view. */
    val libraryId: String?,
    val albumId: String,
    /** The credit exactly as the album or track declares it. */
    val literalCredit: String,
    /** Stable identity of the track inside the repository (source, local id, URI). */
    val trackIdentity: String,
    /** Corroborated MusicBrainz identity for this exact credit, when one exists. */
    val verifiedMbid: String? = null,
)

/**
 * Album-level view retained for callers that only know album counts. It is converted to
 * memberships (one synthetic track identity per declared track) so both paths share the
 * single aggregation algorithm.
 */
data class ArtistCreditOccurrence(
    val credit: String,
    /** Library scope the credit was observed in; null means the aggregate/public view. */
    val libraryId: String?,
    val albumIds: List<String>,
    val trackCount: Int,
    /** Corroborated MusicBrainz identity for this exact credit, when one exists. */
    val mbid: String? = null,
)

enum class ArtistVariantReason {
    /** Both credits are local-only, so no identity can justify a merge. */
    IDENTITY_UNVERIFIED,

    /** Each credit resolves to a different MusicBrainz identity; they are different artists. */
    IDENTITY_CONFLICT,

    /** One credit is verified and the other is not; a merge would inherit unproven identity. */
    MIXED_EVIDENCE,
}

data class ArtistPresentationEntry(
    /** Credit shown in the grid: the first observed literal unless the entry is verified. */
    val credit: String,
    /** Every literal merged into this entry; more than one only with verified identity. */
    val mergedCredits: List<String>,
    val albumIds: List<String>,
    val trackCount: Int,
    val libraryId: String?,
    val mbid: String?,
)

data class ArtistVariantHint(
    val credits: List<String>,
    val reason: ArtistVariantReason,
    val libraryId: String?,
    val albumIds: List<String>,
)

data class ArtistPresentationPlan(
    val entries: List<ArtistPresentationEntry>,
    val variantHints: List<ArtistVariantHint>,
)

/**
 * Builds the plan from real memberships. Entries conserve every album and every distinct
 * track identity they were given; hints only describe possible variants and never move
 * albums between entries.
 */
fun planArtistMemberships(
    memberships: List<ArtistMembershipOccurrence>,
): ArtistPresentationPlan {
    val byCredit = LinkedHashMap<Pair<String?, String>, MutableList<ArtistMembershipOccurrence>>()
    for (membership in memberships) {
        val credit = membership.literalCredit.trim()
        if (credit.isEmpty()) continue
        val key = membership.libraryId to credit
        byCredit.getOrPut(key) { mutableListOf() } += membership.copy(literalCredit = credit)
    }

    // Aggregation is per literal credit: albums and track identities are unioned, never
    // replaced. This is the step that used to drop repeating credits.
    val aggregated = byCredit.map { (key, group) ->
        AggregatedCredit(
            libraryId = key.first,
            credit = key.second,
            albumIds = group.map { it.albumId }.filter { it.isNotEmpty() }.distinct().sorted(),
            trackIdentities = group.map { it.trackIdentity }.filter { it.isNotEmpty() }.distinct(),
            mbidValues = group.mapNotNull { it.normalizedMbid() }.distinct(),
        )
    }

    // Discovery only: credits that orthographically look alike inside one library.
    val discoveryGroups = LinkedHashMap<Pair<String?, String>, MutableList<AggregatedCredit>>()
    for (credit in aggregated) {
        val key = credit.libraryId to artistCreditDiscoveryKey(credit.credit)
        discoveryGroups.getOrPut(key) { mutableListOf() } += credit
    }

    val entries = mutableListOf<ArtistPresentationEntry>()
    val hints = mutableListOf<ArtistVariantHint>()
    for ((_, group) in discoveryGroups) {
        if (group.size == 1) {
            entries += group.single().toEntry()
            continue
        }
        val mergeable = group.all { it.mbidValues.size == 1 }
        val reason = when {
            mergeable && group.map { it.mbidValues.single() }.distinct().size == 1 -> null
            group.all { it.mbidValues.isNotEmpty() } -> ArtistVariantReason.IDENTITY_CONFLICT
            group.any { it.mbidValues.isNotEmpty() } -> ArtistVariantReason.MIXED_EVIDENCE
            else -> ArtistVariantReason.IDENTITY_UNVERIFIED
        }
        if (reason == null) {
            entries += ArtistPresentationEntry(
                credit = group.first().credit,
                mergedCredits = group.map { it.credit }.sortedBy { it.lowercase(Locale.ROOT) },
                albumIds = group.flatMap { it.albumIds }.distinct().sorted(),
                trackCount = group.flatMap { it.trackIdentities }.distinct().size,
                libraryId = group.first().libraryId,
                mbid = group.first().mbidValues.single(),
            )
        } else {
            group.forEach { entries += it.toEntry() }
            hints += ArtistVariantHint(
                credits = group.map { it.credit }.sortedBy { it.lowercase(Locale.ROOT) },
                reason = reason,
                libraryId = group.first().libraryId,
                albumIds = group.flatMap { it.albumIds }.distinct().sorted(),
            )
        }
    }
    return ArtistPresentationPlan(entries = entries, variantHints = hints)
}

/** Album-level entry point kept for callers that only have per-album counts. */
fun planArtistPresentation(occurrences: List<ArtistCreditOccurrence>): ArtistPresentationPlan =
    planArtistMemberships(occurrences.flatMap { it.asMemberships() })

private data class AggregatedCredit(
    val libraryId: String?,
    val credit: String,
    val albumIds: List<String>,
    val trackIdentities: List<String>,
    val mbidValues: List<String>,
) {
    fun toEntry() = ArtistPresentationEntry(
        credit = credit,
        mergedCredits = listOf(credit),
        albumIds = albumIds,
        trackCount = trackIdentities.size,
        libraryId = libraryId,
        mbid = mbidValues.singleOrNull(),
    )
}

private fun ArtistCreditOccurrence.asMemberships(): List<ArtistMembershipOccurrence> {
    val albums = albumIds.distinct()
    if (albums.isEmpty()) {
        return listOf(
            ArtistMembershipOccurrence(
                libraryId = libraryId,
                albumId = "",
                literalCredit = credit,
                trackIdentity = "${credit.lowercase(Locale.ROOT)}#aggregate",
                verifiedMbid = mbid,
            )
        )
    }
    // Spread the declared track count across the albums without losing any of them and
    // without inventing extra tracks: album-level callers keep their exact total.
    return (0 until maxOf(trackCount, albums.size)).map { index ->
        ArtistMembershipOccurrence(
            libraryId = libraryId,
            albumId = albums[index % albums.size],
            literalCredit = credit,
            trackIdentity = "${albums[index % albums.size]}#$index",
            verifiedMbid = mbid,
        )
    }
}

/**
 * Orthographic discovery key. It exists only to find candidates; it is never used to
 * decide that two credits are the same artist.
 */
internal fun artistCreditDiscoveryKey(credit: String): String = credit
    .trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("[\\u2018\\u2019\\u0060\\u00B4]"), "'")
    .replace(Regex("[\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015\\u2212]"), "-")
    // Separators carry no identity: "A-ha", "a ha" and "A. Ha" are the same discovery key.
    .replace(Regex("[^\\p{L}\\p{N}]"), "")

private fun ArtistMembershipOccurrence.normalizedMbid(): String? =
    verifiedMbid?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

/** Human label for the review affordance; reason text stays in the presentation layer. */
fun ArtistVariantHint.displayLabel(): String = credits.joinToString(" · ")
