package org.michimusic.mobile.library

import org.michimusic.data.artist.ArtistConfirmedAlbumIdentityRow
import org.michimusic.data.artist.ArtistTrackEvidenceRecord
import org.michimusic.data.artist.ArtistExplicitTrackIdentityRecord
import org.michimusic.mobile.artist.ArtistMembershipOccurrence
import org.michimusic.mobile.artist.canonicalArtistMbid
import java.util.Locale
import kotlinx.coroutines.ensureActive

/** D36: route-local identity is keyed by album AND exact track, never by artist name. */
internal data class ArtistExactOccurrenceClaim(
    val libraryId: String,
    val albumId: String,
    val albumCredit: String,
    val literalCredit: String,
    val trackIdentity: String,
    val trackId: String?,
    /** Stable catalogue-level file facts; never exported, persisted only as SHA-256. */
    val localContentSignature: String = "",
)

internal data class ArtistExactOccurrenceKey(val albumId: String, val trackIdentity: String)

/** Untrusted metadata stays separate from durable, user-confirmed album bindings. */
internal data class ArtistExactOccurrenceIdentity(
    val confirmedMbid: String?,
    val untrustedTagMbids: List<String>,
    val confirmedByExactTrackChoice: Boolean = false,
    val confirmedAlbumMbid: String? = null,
)

internal sealed interface ArtistExactOccurrenceIdentityResult {
    data class Ready(
        val identities: Map<ArtistExactOccurrenceKey, ArtistExactOccurrenceIdentity>,
    ) : ArtistExactOccurrenceIdentityResult {
        /** Never promote observations from embedded tags to verified identity. */
        fun applyTo(rows: List<ArtistMembershipOccurrence>): List<ArtistMembershipOccurrence> =
            rows.map { row ->
                row.copy(verifiedMbid = identities[
                    ArtistExactOccurrenceKey(row.albumId, row.trackIdentity)
                ]?.confirmedMbid,
                exactTrackConfirmed = identities[
                    ArtistExactOccurrenceKey(row.albumId, row.trackIdentity)
                ]?.confirmedByExactTrackChoice == true)
            }
    }
    data class Blocked(val reason: Reason) : ArtistExactOccurrenceIdentityResult
    enum class Reason {
        NO_EXACT_LIBRARY, TOO_MANY_ROWS, INVALID_CLAIM, DUPLICATE_OCCURRENCE,
        CROSS_LIBRARY_EVIDENCE, UNKNOWN_EVIDENCE, DUPLICATE_BINDING,
        INVALID_CONFIRMED_IDENTITY, TRACK_ID_COLLISION,
    }
}

/** Pure and deterministic: evidence is supplied by Room, but the policy never queries Room. */
internal object ArtistExactOccurrenceIdentityPolicy {
    // Output remains an in-memory map; this is a bounded catalogue, not unlimited streaming.
    const val MAX_OCCURRENCES = 65_536
    const val PAGE_SIZE = 256
    const val MAX_CLAIM_TEXT_UNITS = 16_777_216L
    private const val MAX_OBSERVED_MBIDS = 262_144L
    private const val MAX_TAG_EVIDENCE = 65_536
    private val explicitBindingReasons = setOf(
        "USER_EXPLICIT_IDENTITY_CONFIRMATION", "USER_EXPLICIT_BINDING", "USER_EXPLICIT_REPLACEMENT",
    )
    private val tagMbidFields = setOf("ARTIST_MBID", "AUTO_ARTIST_MBID")

    fun reconcile(
        libraryId: String?,
        claims: List<ArtistExactOccurrenceClaim>,
        confirmedAlbums: List<ArtistConfirmedAlbumIdentityRow>,
        tagEvidence: List<ArtistTrackEvidenceRecord>,
        explicitTrackChoices: List<ArtistExplicitTrackIdentityRecord> = emptyList(),
        rootSourceId: String = "",
        rootEpoch: Long = -1,
        catalogRevision: Long = -1,
    ): ArtistExactOccurrenceIdentityResult {
        fun blocked(reason: ArtistExactOccurrenceIdentityResult.Reason) =
            ArtistExactOccurrenceIdentityResult.Blocked(reason)
        validateClaims(libraryId, claims)?.let { return blocked(it) }
        if (tagEvidence.size > MAX_TAG_EVIDENCE || confirmedAlbums.size > MAX_OCCURRENCES ||
            explicitTrackChoices.size > MAX_OCCURRENCES)
            return blocked(ArtistExactOccurrenceIdentityResult.Reason.TOO_MANY_ROWS)
        val authorizedPairs = claims.mapTo(HashSet()) { it.albumId to norm(it.literalCredit) }
        val authorizedTrackIds = claims.mapNotNullTo(HashSet()) { it.trackId }

        val byAlbumCredit = HashMap<Pair<String, String>, String>()
        for (binding in confirmedAlbums) {
            if (binding.libraryId != libraryId)
                return blocked(ArtistExactOccurrenceIdentityResult.Reason.CROSS_LIBRARY_EVIDENCE)
            val pair = binding.albumKey to binding.creditKey
            if (pair !in authorizedPairs)
                return blocked(ArtistExactOccurrenceIdentityResult.Reason.UNKNOWN_EVIDENCE)
            if (byAlbumCredit.containsKey(pair))
                return blocked(ArtistExactOccurrenceIdentityResult.Reason.DUPLICATE_BINDING)
            if (binding.evidenceType !in explicitBindingReasons ||
                binding.confirmedAtMs < 0 || binding.associationId.isBlank())
                return blocked(ArtistExactOccurrenceIdentityResult.Reason.UNKNOWN_EVIDENCE)
            val canonical = canonicalArtistMbid(binding.confirmedMbid)
            if (canonical == null || canonical != binding.confirmedMbid)
                return blocked(ArtistExactOccurrenceIdentityResult.Reason.INVALID_CONFIRMED_IDENTITY)
            byAlbumCredit[pair] = canonical
        }

        val byTrack = HashMap<String, MutableSet<String>>()
        for (row in tagEvidence) {
            if (row.libraryId != libraryId)
                return blocked(ArtistExactOccurrenceIdentityResult.Reason.CROSS_LIBRARY_EVIDENCE)
            if (row.trackId !in authorizedTrackIds || row.field !in tagMbidFields ||
                row.ordinal !in 0..31)
                return blocked(ArtistExactOccurrenceIdentityResult.Reason.UNKNOWN_EVIDENCE)
            // Bad embedded IDs are ignored, never proof and never fatal to local playback.
            canonicalArtistMbid(row.value)?.let { mbid ->
                byTrack.getOrPut(row.trackId) { linkedSetOf() }.add(mbid)
            }
        }
        val choices = HashMap<Triple<String, String, String>, ArtistExplicitTrackIdentityRecord>()
        // Invalidated old root/track receipts are ignored, never used as an MBID.
        for (row in explicitTrackChoices) {
            if (row.libraryId != libraryId)
                return blocked(ArtistExactOccurrenceIdentityResult.Reason.CROSS_LIBRARY_EVIDENCE)
            val pair = Triple(row.albumKey, row.trackId, row.creditKey)
            if (choices.putIfAbsent(pair, row) != null)
                return blocked(ArtistExactOccurrenceIdentityResult.Reason.DUPLICATE_BINDING)
        }
        val result = LinkedHashMap<ArtistExactOccurrenceKey, ArtistExactOccurrenceIdentity>()
        for (claim in claims) {
            val credit = norm(claim.literalCredit)
            // Album-level identity is proof only for its OWN principal album artist.
            // A guest track with the same credit elsewhere needs its own confirmation.
            val isAlbumCredit = credit == norm(claim.albumCredit)
            val observed = claim.trackId?.let { byTrack[it].orEmpty().sorted() }.orEmpty()
            val albumConfirmed = if (isAlbumCredit) byAlbumCredit[claim.albumId to credit] else null
            // A contradictory embedded MBID cannot replace the manual album choice,
            // but it can signal an unresolved same-name guest in that album. Fail closed
            // for the affected occurrence; never apply the album identity to that track.
            val confirmed = albumConfirmed?.takeIf { it in observed || observed.isEmpty() }
                ?.takeIf { observed.size <= 1 }
            val chosen = claim.trackId?.let { id ->
                choices[Triple(claim.albumId, id, credit)]
            }?.takeIf { row ->
                ArtistExplicitTrackChoicePolicy.matches(row, claim,
                    rootSourceId, rootEpoch, catalogRevision)
            }
            // An explicit reviewed choice is local per-track authority. Raw MBID tags
            // remain observations and never override the chosen user decision.
            result[ArtistExactOccurrenceKey(claim.albumId, claim.trackIdentity)] =
                ArtistExactOccurrenceIdentity(
                    confirmedMbid = chosen?.confirmedMbid ?: confirmed,
                    untrustedTagMbids = observed,
                    confirmedByExactTrackChoice = chosen != null,
                    confirmedAlbumMbid = albumConfirmed,
                )
        }
        return ArtistExactOccurrenceIdentityResult.Ready(result)
    }

    fun claimTextUnits(claim: ArtistExactOccurrenceClaim): Long =
        claim.libraryId.length.toLong() + claim.albumId.length + claim.albumCredit.length +
            claim.literalCredit.length + claim.trackIdentity.length + (claim.trackId?.length ?: 0) +
            claim.localContentSignature.length

    /** Global admission runs BEFORE any page is read, including collisions across distant pages. */
    fun validateClaims(libraryId: String?, claims: List<ArtistExactOccurrenceClaim>):
        ArtistExactOccurrenceIdentityResult.Reason? {
        if (libraryId.isNullOrBlank() || libraryId == "SCOPE_ALL")
            return ArtistExactOccurrenceIdentityResult.Reason.NO_EXACT_LIBRARY
        if (claims.isEmpty() || claims.size > MAX_OCCURRENCES)
            return ArtistExactOccurrenceIdentityResult.Reason.TOO_MANY_ROWS
        val keys = HashSet<ArtistExactOccurrenceKey>()
        val owners = HashMap<String, ArtistExactOccurrenceKey>()
        var textUnits = 0L
        for (claim in claims) {
            if (claim.libraryId != libraryId || claim.albumId.isBlank() ||
                claim.albumCredit.isBlank() || claim.literalCredit.isBlank() ||
                claim.trackIdentity.isBlank() || claim.trackId?.isBlank() == true)
                return ArtistExactOccurrenceIdentityResult.Reason.INVALID_CLAIM
            textUnits += claimTextUnits(claim)
            if (textUnits > MAX_CLAIM_TEXT_UNITS)
                return ArtistExactOccurrenceIdentityResult.Reason.TOO_MANY_ROWS
            val key = ArtistExactOccurrenceKey(claim.albumId, claim.trackIdentity)
            if (!keys.add(key)) return ArtistExactOccurrenceIdentityResult.Reason.DUPLICATE_OCCURRENCE
            claim.trackId?.let { id ->
                if (owners.putIfAbsent(id, key) != null)
                    return ArtistExactOccurrenceIdentityResult.Reason.TRACK_ID_COLLISION
            }
        }
        return null
    }

    /** Caller must use one consistent read transaction for EVERY callback. No partial publication. */
    suspend fun reconcilePages(
        libraryId: String?, claims: List<ArtistExactOccurrenceClaim>,
        readPage: suspend (List<ArtistExactOccurrenceClaim>) -> ArtistExactOccurrenceIdentityResult,
    ): ArtistExactOccurrenceIdentityResult {
        validateClaims(libraryId, claims)?.let { return ArtistExactOccurrenceIdentityResult.Blocked(it) }
        val result = LinkedHashMap<ArtistExactOccurrenceKey, ArtistExactOccurrenceIdentity>()
        var observedCount = 0L
        for (start in claims.indices step PAGE_SIZE) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val page = claims.subList(start, minOf(start + PAGE_SIZE, claims.size))
            when (val resolved = readPage(page)) {
                is ArtistExactOccurrenceIdentityResult.Blocked -> return resolved
                is ArtistExactOccurrenceIdentityResult.Ready -> {
                    val expected = page.mapTo(HashSet()) { ArtistExactOccurrenceKey(it.albumId, it.trackIdentity) }
                    if (resolved.identities.keys != expected)
                        return ArtistExactOccurrenceIdentityResult.Blocked(ArtistExactOccurrenceIdentityResult.Reason.UNKNOWN_EVIDENCE)
                    for ((key, identity) in resolved.identities) {
                        observedCount += identity.untrustedTagMbids.size
                        if (observedCount > MAX_OBSERVED_MBIDS)
                            return ArtistExactOccurrenceIdentityResult.Blocked(ArtistExactOccurrenceIdentityResult.Reason.TOO_MANY_ROWS)
                        result[key] = identity
                    }
                }
            }
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return ArtistExactOccurrenceIdentityResult.Ready(result)
    }

    private fun norm(value: String): String = value.trim().lowercase(Locale.ROOT)
}
