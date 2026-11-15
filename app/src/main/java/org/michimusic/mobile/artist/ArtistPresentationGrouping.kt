package org.michimusic.mobile.artist

import java.util.Locale

/**
 * K48-S5 — how artist credits become presentation groups.
 *
 * A local credit is metadata, never proof of identity. Two credits that differ only in
 * capitalisation, spacing or punctuation (`A-ha` / `a-ha`) are only *discovery*
 * candidates: they share a presentation entry exclusively when a verified MusicBrainz
 * identity backs both, and never when the identities conflict, when only one is
 * verified, or when the credits come from different libraries.
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
 * Builds the presentation plan. Entries conserve every album and track they were given;
 * hints only describe possible variants and never move albums between entries.
 */
fun planArtistPresentation(occurrences: List<ArtistCreditOccurrence>): ArtistPresentationPlan {
    val groups = LinkedHashMap<Pair<String?, String>, MutableList<ArtistCreditOccurrence>>()
    for (occurrence in occurrences) {
        val credit = occurrence.credit.trim()
        if (credit.isEmpty()) continue
        val key = occurrence.libraryId to artistCreditDiscoveryKey(credit)
        groups.getOrPut(key) { mutableListOf() } += occurrence.copy(credit = credit)
    }

    val entries = mutableListOf<ArtistPresentationEntry>()
    val hints = mutableListOf<ArtistVariantHint>()
    for ((_, members) in groups) {
        val distinct = members.distinctBy { it.credit }
        if (distinct.size == 1) {
            entries += distinct.single().toEntry(mbid = distinct.single().normalizedMbid())
            continue
        }
        val verified = distinct.map { it.normalizedMbid() }
        val reason = when {
            verified.all { it != null } && verified.distinct().size == 1 -> null
            verified.all { it != null } -> ArtistVariantReason.IDENTITY_CONFLICT
            verified.any { it != null } -> ArtistVariantReason.MIXED_EVIDENCE
            else -> ArtistVariantReason.IDENTITY_UNVERIFIED
        }
        if (reason == null) {
            entries += ArtistPresentationEntry(
                credit = distinct.first().credit,
                mergedCredits = distinct.map { it.credit }.sortedBy { it.lowercase(Locale.ROOT) },
                albumIds = distinct.flatMap { it.albumIds }.distinct().sorted(),
                trackCount = distinct.sumOf { it.trackCount },
                libraryId = distinct.first().libraryId,
                mbid = verified.filterNotNull().first(),
            )
        } else {
            distinct.forEach { entries += it.toEntry(mbid = it.normalizedMbid()) }
            hints += ArtistVariantHint(
                credits = distinct.map { it.credit }.sortedBy { it.lowercase(Locale.ROOT) },
                reason = reason,
                libraryId = distinct.first().libraryId,
                albumIds = distinct.flatMap { it.albumIds }.distinct().sorted(),
            )
        }
    }
    return ArtistPresentationPlan(entries = entries, variantHints = hints)
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

private fun ArtistCreditOccurrence.toEntry(mbid: String?) = ArtistPresentationEntry(
    credit = credit,
    mergedCredits = listOf(credit),
    albumIds = albumIds.distinct().sorted(),
    trackCount = trackCount,
    libraryId = libraryId,
    mbid = mbid,
)

private fun ArtistCreditOccurrence.normalizedMbid(): String? =
    mbid?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

/** Human label for the review affordance; reason text stays in the presentation layer. */
fun ArtistVariantHint.displayLabel(): String = credits.joinToString(" · ")
