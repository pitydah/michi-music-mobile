package org.michimusic.mobile.artist.artwork

import org.michimusic.mobile.artist.ArtistExternalProfile
import org.michimusic.mobile.artist.canonicalArtistMbid
import org.michimusic.mobile.artist.scopedArtistCreditKey

/** Two independent render slots. A single downloaded file may serve both. */
enum class ArtworkSlot { ARTIST_THUMB, ARTIST_HERO }

enum class ArtworkSource {
    COMMONS_P18,
    COMMONS_P180,
    WIKIPEDIA_PAGEIMAGE,
    COMMONS_CATEGORY,
    OPENVERSE,
    THEAUDIODB,
    FANART_TV,
}

enum class ArtworkEvidence {
    VERIFIED_QID_REFERENCE,
    VERIFIED_ARTICLE_LINK,
    USER_SELECTED,
    UNVERIFIED,
}

enum class ArtworkRights { VERIFIED_ALLOWED, REQUIRES_REVIEW, REJECTED }

/** UI-visible state; READY is emitted only after bytes exist locally and decode. */
enum class ArtworkLoadStatus {
    NOT_REQUESTED,
    FETCHING,
    READY,
    PARTIAL,
    NO_IMAGE,
    REJECTED,
    PROVIDER_ERROR,
    CACHE_ERROR,
    IDENTITY_MISMATCH,
}

enum class ArtworkProviderStatus { AVAILABLE, NO_MATCH, FAILURE, DISABLED, SKIPPED }

data class ArtistIdentityAnchor(
    val libraryId: String,
    val creditKey: String,
    val albumGroupKey: String,
    val confirmedMbid: String,
    val verifiedQid: String?,
    /** Only used for explicitly requested Openverse discovery, never identity. */
    val displayCredit: String,
)

data class ArtistArtworkCandidate(
    val source: ArtworkSource,
    val stableAssetId: String,
    val confirmedMbid: String,
    val verifiedQid: String?,
    val canonicalSourcePage: String,
    val imageUrl: String,
    val originalWidth: Int?,
    val originalHeight: Int?,
    val mimeType: String?,
    val licenseId: String?,
    val licenseUrl: String?,
    val author: String?,
    val attribution: String?,
    val evidence: ArtworkEvidence,
    val rights: ArtworkRights,
    /** Populated only after a verified local download. */
    val localRelativePath: String? = null,
    val downloadedSha256: String? = null,
)

data class ArtworkFocus(val x: Float = 0.5f, val y: Float = 0.5f) {
    fun normalized(): ArtworkFocus = ArtworkFocus(
        x = x.coerceIn(0f, 1f),
        y = y.coerceIn(0f, 1f),
    )
}

data class ArtistArtworkSelection(
    val slot: ArtworkSlot,
    val assetId: String,
    val confirmedMbid: String,
    val localImagePath: String?,
    val focus: ArtworkFocus,
    val manuallyChosen: Boolean,
    val revision: Long,
    val sourcePageUrl: String? = null,
    val licenseId: String? = null,
    val licenseUrl: String? = null,
    val attribution: String? = null,
    val source: ArtworkSource? = null,
    val status: ArtworkLoadStatus = ArtworkLoadStatus.READY,
)

data class ArtistArtworkSlotState(
    val slot: ArtworkSlot,
    val selection: ArtistArtworkSelection? = null,
    val status: ArtworkLoadStatus = ArtworkLoadStatus.NOT_REQUESTED,
    val reason: String? = null,
)

data class ArtistArtworkUiState(
    val thumb: ArtistArtworkSlotState = ArtistArtworkSlotState(ArtworkSlot.ARTIST_THUMB),
    val hero: ArtistArtworkSlotState = ArtistArtworkSlotState(ArtworkSlot.ARTIST_HERO),
) {
    val hasRenderableArtwork: Boolean
        get() = thumb.selection?.localImagePath != null || hero.selection?.localImagePath != null
}

data class ArtworkProviderOutcome(
    val source: ArtworkSource,
    val status: ArtworkProviderStatus,
    val reason: String? = null,
    val candidateCount: Int = 0,
)

data class ArtworkResolutionReport(
    val status: ArtworkLoadStatus,
    val outcomes: List<ArtworkProviderOutcome>,
    val selectedAssets: Map<ArtworkSlot, String?> = emptyMap(),
    val selections: Map<ArtworkSlot, ArtistArtworkSelection> = emptyMap(),
    val message: String? = null,
    val fromCache: Boolean = false,
)

sealed interface ProviderArtworkResult {
    data class Available(val items: List<ArtistArtworkCandidate>) : ProviderArtworkResult
    data object NoMatch : ProviderArtworkResult
    data class Failure(val type: String, val retryable: Boolean) : ProviderArtworkResult
    data class Disabled(val reason: String) : ProviderArtworkResult
}

/** A manual Openverse result is never allowed to become an automatic selection. */
fun ArtistArtworkCandidate.isAutoEligible(): Boolean =
    rights == ArtworkRights.VERIFIED_ALLOWED &&
        evidence != ArtworkEvidence.UNVERIFIED &&
        evidence != ArtworkEvidence.USER_SELECTED &&
        source != ArtworkSource.OPENVERSE

fun ArtistArtworkCandidate.isManualEligible(): Boolean =
    rights != ArtworkRights.REJECTED && source == ArtworkSource.OPENVERSE

/**
 * The only path from a local artist and a confirmed MusicBrainz profile to artwork queries.
 * No MBID, no local key or a mismatched MBID fails closed; nothing is inferred from the name.
 */
fun artistIdentityAnchor(
    libraryId: String?,
    credit: String,
    albumIds: List<String>,
    profile: ArtistExternalProfile?,
    associationGroupKey: String? = null,
): ArtistIdentityAnchor? {
    val mbid = canonicalArtistMbid(profile?.mbid) ?: return null
    if (profile == null || profile.mbid != mbid) return null
    val (creditKey, currentGroupKey) = scopedArtistCreditKey(libraryId, credit, albumIds) ?: return null
    val groupKey = associationGroupKey?.takeIf { it.isNotBlank() } ?: currentGroupKey
    val qid = profile.wikidataQid?.trim()?.takeIf(::validWikidataQid)
    val display = credit.trim().take(120).ifBlank { return null }
    return ArtistIdentityAnchor(
        libraryId = libraryId!!,
        creditKey = creditKey,
        albumGroupKey = groupKey,
        confirmedMbid = mbid,
        verifiedQid = qid,
        displayCredit = display,
    )
}

fun validWikidataQid(value: String?): Boolean =
    value != null && Regex("^Q[1-9][0-9]{0,10}$").matches(value)

fun canonicalCommonsAssetId(fileTitle: String): String? {
    val raw = fileTitle.trim().removePrefix("File:").replace('_', ' ').trim()
    if (raw.isEmpty() || raw.length > 180 || '/' in raw || '\\' in raw) return null
    return "commons:${raw.lowercase(java.util.Locale.ROOT)}"
}

/** Stable tie-break: never provider arrival order, never randomness. */
fun artworkRankingComparator(slot: ArtworkSlot): Comparator<ArtistArtworkCandidate> =
    compareBy<ArtistArtworkCandidate> { sourcePriority(it.source) }
        .thenBy { aspectPenalty(it, slot) }
        .thenByDescending { resolutionScore(it) }
        .thenBy { it.stableAssetId }

private fun sourcePriority(source: ArtworkSource): Int = when (source) {
    ArtworkSource.COMMONS_P18 -> 0
    ArtworkSource.COMMONS_P180 -> 1
    ArtworkSource.WIKIPEDIA_PAGEIMAGE -> 2
    ArtworkSource.COMMONS_CATEGORY -> 3
    ArtworkSource.OPENVERSE -> 4
    ArtworkSource.THEAUDIODB -> 5
    ArtworkSource.FANART_TV -> 6
}

/** Lower is better; the slot only expresses a preference, never a licence exemption. */
private fun aspectPenalty(candidate: ArtistArtworkCandidate, slot: ArtworkSlot): Int {
    val width = candidate.originalWidth ?: return 20
    val height = candidate.originalHeight ?: return 20
    if (width <= 0 || height <= 0) return 20
    val ratio = width.toFloat() / height.toFloat()
    val target = if (slot == ArtworkSlot.ARTIST_THUMB) 1f else 16f / 9f
    return (kotlin.math.abs(ratio - target) * 100f).toInt().coerceIn(0, 99)
}

private fun resolutionScore(candidate: ArtistArtworkCandidate): Long {
    val width = candidate.originalWidth?.toLong() ?: 0L
    val height = candidate.originalHeight?.toLong() ?: 0L
    return width * height
}

/** A hero may reuse a thumb only when its pixels can survive a wide crop. */
fun canReuseThumbAsHero(candidate: ArtistArtworkCandidate): Boolean {
    val width = candidate.originalWidth ?: return false
    val height = candidate.originalHeight ?: return false
    return width >= 800 && height >= 500 && width >= height
}
