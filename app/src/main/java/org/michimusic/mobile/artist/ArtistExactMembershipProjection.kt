package org.michimusic.mobile.artist

import java.security.MessageDigest
import java.util.Locale

/**
 * D33: a read-only projection for artist navigation, based exclusively on the exact
 * occurrences already read from the authorized ONE library. Not a profile/MBID grant.
 * `trackIdentity` is kept in memory only: it may contain local URIs and MUST NOT be
 * serialized into a navigation route or sent to a provider.
 */
data class ArtistExactTrackMember(
    val albumId: String,
    val literalCredit: String,
    val trackIdentity: String,
)

data class ArtistExactMembershipSnapshot(
    val libraryId: String,
    val displayCredit: String,
    val mergedCredits: List<String>,
    val verifiedMbid: String?,
    val members: List<ArtistExactTrackMember>,
    val membershipDigest: String,
) {
    val albumIds: List<String> get() = members.map { it.albumId }.distinct().sorted()

    /** In addition to root-generation checks, always re-read exact membership on arrival. */
    fun revalidate(
        currentEntry: ArtistPresentationEntry,
        currentOccurrences: List<ArtistMembershipOccurrence>,
        currentLibraryId: String,
    ): Boolean = ArtistExactMembershipProjection.prepare(
        currentEntry, currentOccurrences, currentLibraryId,
    ) == ArtistExactMembershipResult.Ready(this)
}

sealed interface ArtistExactMembershipResult {
    data class Ready(val snapshot: ArtistExactMembershipSnapshot) : ArtistExactMembershipResult
    data class Blocked(val reason: Reason) : ArtistExactMembershipResult

    enum class Reason {
        MISSING_EXACT_LIBRARY, INVALID_CREDIT, WRONG_LIBRARY, INVALID_ENTRY,
        MEMBERSHIP_MISSING, ALBUM_MISMATCH, TRACK_COUNT_MISMATCH,
        DUPLICATE_TRACK_OWNER, UNVERIFIED_MERGE, IDENTITY_CONFLICT, LIMIT_EXCEEDED,
    }
}

/**
 * Pure fail-closed membership verifier. In particular, an MBID on the mosaic entry
 * is insufficient unless EVERY participating occurrence independently carries it.
 * A single unverified credit may be browsed locally but cannot gain profile authority.
 */
object ArtistExactMembershipProjection {
    private const val MAX_MEMBERS = 2048
    private const val MAX_ALBUMS = 512
    private const val MAX_CREDITS = 64
    private const val MAX_TRACK_KEY_CHARS = 2048
    private val UUID = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$")
    private val ZERO_UUID = "00000000-0000-0000-0000-000000000000"

    fun prepare(
        entry: ArtistPresentationEntry,
        occurrences: List<ArtistMembershipOccurrence>,
        exactLibraryId: String,
    ): ArtistExactMembershipResult {
        fun blocked(reason: ArtistExactMembershipResult.Reason) =
            ArtistExactMembershipResult.Blocked(reason)
        val lib = exactLibraryId.trim()
        if (lib.isEmpty() || lib != exactLibraryId) {
            return blocked(ArtistExactMembershipResult.Reason.MISSING_EXACT_LIBRARY)
        }
        if (entry.libraryId != lib) return blocked(ArtistExactMembershipResult.Reason.WRONG_LIBRARY)
        val credits = entry.mergedCredits
        if (credits.isEmpty() || credits.size > MAX_CREDITS || credits.distinct().size != credits.size ||
            credits.any { it.isBlank() || it.length > 160 || it != it.trim() } ||
            entry.credit !in credits || entry.credit.isBlank()
        ) return blocked(ArtistExactMembershipResult.Reason.INVALID_CREDIT)
        val declaredAlbums = entry.albumIds
        if (declaredAlbums.isEmpty() || declaredAlbums.size > MAX_ALBUMS ||
            declaredAlbums.any { it.isBlank() || it != it.trim() } ||
            declaredAlbums.distinct().size != declaredAlbums.size || entry.trackCount <= 0
        ) return blocked(ArtistExactMembershipResult.Reason.INVALID_ENTRY)
        val expectedMbid = entry.mbid?.trim()?.lowercase(Locale.ROOT)
        if (expectedMbid != null && (!UUID.matches(expectedMbid) || expectedMbid == ZERO_UUID)) {
            return blocked(ArtistExactMembershipResult.Reason.IDENTITY_CONFLICT)
        }
        if (credits.size > 1 && expectedMbid == null) {
            return blocked(ArtistExactMembershipResult.Reason.UNVERIFIED_MERGE)
        }
        // Do not use an artist spelling or discovery key to attribute other albums.
        val sameCredit = occurrences.filter { it.libraryId == lib && it.literalCredit in credits }
        val candidates = if (entry.identityPartitioned) {
            if (credits.size != 1 || expectedMbid == null || sameCredit.isEmpty() ||
                sameCredit.any { it.verifiedMbid == null || canonicalArtistMbid(it.verifiedMbid) == null })
                return blocked(ArtistExactMembershipResult.Reason.IDENTITY_CONFLICT)
            // Same-album different MBIDs require explicit user-confirmed per-track
            // receipts for every participating occurrence, not inferred album ownership.
            if (sameCredit.groupBy { it.albumId }.values.any { albumRows ->
                    albumRows.map { canonicalArtistMbid(it.verifiedMbid) }.distinct().size != 1 &&
                        albumRows.any { !it.exactTrackConfirmed }
                }) return blocked(ArtistExactMembershipResult.Reason.IDENTITY_CONFLICT)
            sameCredit.filter { canonicalArtistMbid(it.verifiedMbid) == expectedMbid }
        } else sameCredit
        if (candidates.isEmpty()) return blocked(ArtistExactMembershipResult.Reason.MEMBERSHIP_MISSING)
        if (candidates.size > MAX_MEMBERS) return blocked(ArtistExactMembershipResult.Reason.LIMIT_EXCEEDED)
        if (candidates.any { it.albumId.isBlank() || it.trackIdentity.isBlank() ||
                it.trackIdentity.length > MAX_TRACK_KEY_CHARS || it.literalCredit != it.literalCredit.trim() }
        ) return blocked(ArtistExactMembershipResult.Reason.INVALID_ENTRY)
        if (candidates.map { it.albumId }.distinct().sorted() != declaredAlbums.sorted()) {
            return blocked(ArtistExactMembershipResult.Reason.ALBUM_MISMATCH)
        }
        if (credits.any { credit -> candidates.none { it.literalCredit == credit } }) {
            return blocked(ArtistExactMembershipResult.Reason.MEMBERSHIP_MISSING)
        }
        // A track may not belong to two different artists or albums within one projected entry.
        val ownerByTrack = candidates.groupBy { it.trackIdentity }
        if (ownerByTrack.any { (_, owners) -> owners.map { it.albumId to it.literalCredit }.distinct().size != 1 }) {
            return blocked(ArtistExactMembershipResult.Reason.DUPLICATE_TRACK_OWNER)
        }
        val members = candidates.distinctBy { Triple(it.albumId, it.literalCredit, it.trackIdentity) }
            .map { ArtistExactTrackMember(it.albumId, it.literalCredit, it.trackIdentity) }
            .sortedWith(compareBy({ it.albumId }, { it.literalCredit }, { it.trackIdentity }))
        if (members.size != entry.trackCount) {
            return blocked(ArtistExactMembershipResult.Reason.TRACK_COUNT_MISMATCH)
        }
        if (candidates.any { occurrence ->
                val actual = occurrence.verifiedMbid?.trim()?.lowercase(Locale.ROOT)
                actual != expectedMbid
            }
        ) return blocked(ArtistExactMembershipResult.Reason.IDENTITY_CONFLICT)
        val digest = digest(lib, entry.credit, credits.sorted(), expectedMbid, members)
        return ArtistExactMembershipResult.Ready(ArtistExactMembershipSnapshot(
            libraryId = lib,
            displayCredit = entry.credit,
            mergedCredits = credits.sorted(),
            verifiedMbid = expectedMbid,
            members = members,
            membershipDigest = digest,
        ))
    }

    private fun digest(
        libraryId: String, displayCredit: String, credits: List<String>,
        mbid: String?, members: List<ArtistExactTrackMember>,
    ): String {
        val hash = MessageDigest.getInstance("SHA-256")
        // Length-delimited to prevent ambiguity when credits/URIs contain separators.
        fun feed(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val size = bytes.size
            hash.update(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(),
                (size ushr 8).toByte(), size.toByte()))
            hash.update(bytes)
        }
        feed("michi.artist.exact-membership.v1")
        feed(libraryId)
        feed(displayCredit)
        feed(mbid ?: "")
        feed(credits.size.toString())
        credits.forEach(::feed)
        feed(members.size.toString())
        members.forEach { feed(it.albumId); feed(it.literalCredit); feed(it.trackIdentity) }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
