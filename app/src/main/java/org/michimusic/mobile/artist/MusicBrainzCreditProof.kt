package org.michimusic.mobile.artist

/**
 * Independent MusicBrainz credit corroboration. Nothing here writes an artist identity,
 * chooses a photo, or trusts a display name. Corroborated still means REVIEW_ONLY
 * under ARTIST_ENRICHMENT_DOMAIN_FINAL until its authority contract is amended.
 */
enum class VerifiedCreditRole { TRACK_ARTIST, ALBUM_ARTIST }

data class MusicBrainzCreditClaim(
    val artistMbid: String,
    val recordingMbid: String?,
    val releaseMbid: String?,
    val role: VerifiedCreditRole,
)

internal data class MusicBrainzRecordingCredit(
    val recordingMbid: String,
    val artistMbids: Set<String>,
)

internal data class MusicBrainzReleaseCredit(
    val releaseMbid: String,
    val albumArtistMbids: Set<String>,
    val recordingMbids: Set<String>,
    /** Optional structured artist credit on each recording's *release track*. */
    val trackArtistMbids: Map<String, Set<String>> = emptyMap(),
)

sealed interface MusicBrainzCreditProofResult {
    /** This is evidence for a visible review proposal, NOT automatic identity approval. */
    data class CorroboratedForReview(val artistMbid: String, val role: VerifiedCreditRole) : MusicBrainzCreditProofResult
    data class Blocked(val reason: Reason) : MusicBrainzCreditProofResult
    enum class Reason {
        MISSING_STRUCTURED_IDS, MALFORMED_MBID, RESPONSE_ID_MISMATCH,
        RECORDING_NOT_ON_RELEASE, ARTIST_NOT_CREDITED, MULTIPLE_CREDITED_ARTISTS,
        MISSING_RESPONSE, BUDGET_EXCEEDED, INVALID_REMOTE_CREDIT,
    }
}

internal object MusicBrainzCreditProof {
    private val uuid = Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private fun canonical(value: String?): String? = value?.trim()?.takeIf(uuid::matches)?.lowercase(java.util.Locale.ROOT)

    fun evaluate(
        claim: MusicBrainzCreditClaim,
        recording: MusicBrainzRecordingCredit?,
        release: MusicBrainzReleaseCredit?,
    ): MusicBrainzCreditProofResult {
        fun blocked(reason: MusicBrainzCreditProofResult.Reason) = MusicBrainzCreditProofResult.Blocked(reason)
        val artist = canonical(claim.artistMbid) ?: return blocked(MusicBrainzCreditProofResult.Reason.MALFORMED_MBID)
        val recordingId = canonical(claim.recordingMbid)
        val releaseId = canonical(claim.releaseMbid)
        // A recording and an exact release are required to guard album ownership.
        if (claim.recordingMbid.isNullOrBlank() || claim.releaseMbid.isNullOrBlank()) {
            return blocked(MusicBrainzCreditProofResult.Reason.MISSING_STRUCTURED_IDS)
        }
        if (recordingId == null || releaseId == null) return blocked(MusicBrainzCreditProofResult.Reason.MALFORMED_MBID)
        if (recording == null || release == null) return blocked(MusicBrainzCreditProofResult.Reason.MISSING_RESPONSE)
        if (canonical(recording.recordingMbid) != recordingId || canonical(release.releaseMbid) != releaseId) {
            return blocked(MusicBrainzCreditProofResult.Reason.RESPONSE_ID_MISMATCH)
        }
        // Never accept a release just because its name resembles the local album.
        // The parser validates JSON, but this pure policy also receives models from
        // fixtures and future callers. Never drop an invalid remote ID with mapNotNull:
        // that can turn a collaboration into a falsely unique artist credit.
        val releaseRecordingIds = release.recordingMbids
        if (releaseRecordingIds.size > 512) return blocked(MusicBrainzCreditProofResult.Reason.BUDGET_EXCEEDED)
        if (releaseRecordingIds.isEmpty()) return blocked(MusicBrainzCreditProofResult.Reason.MISSING_RESPONSE)
        val normalizedRecordings = releaseRecordingIds.mapNotNull(::canonical).toSet()
        if (normalizedRecordings.size != releaseRecordingIds.size) {
            return blocked(MusicBrainzCreditProofResult.Reason.INVALID_REMOTE_CREDIT)
        }
        if (recordingId !in normalizedRecordings) {
            return blocked(MusicBrainzCreditProofResult.Reason.RECORDING_NOT_ON_RELEASE)
        }
        // The recording artist-credit and the release track artist-credit are
        // distinct authorities: compilations can differ. If release-track credits
        // are supplied, reject a bad or contradictory credit rather than ignoring it.
        if (release.trackArtistMbids.size > 512) return blocked(MusicBrainzCreditProofResult.Reason.BUDGET_EXCEEDED)
        val normalizedTrackCredits = LinkedHashMap<String, Set<String>>()
        release.trackArtistMbids.forEach { (id, ids) ->
            val key = canonical(id) ?: return blocked(MusicBrainzCreditProofResult.Reason.INVALID_REMOTE_CREDIT)
            if (ids.isEmpty() || ids.size > 16) return blocked(MusicBrainzCreditProofResult.Reason.INVALID_REMOTE_CREDIT)
            val normalized = ids.mapNotNull(::canonical).toSet()
            if (normalized.size != ids.size || normalizedTrackCredits.putIfAbsent(key, normalized) != null) {
                return blocked(MusicBrainzCreditProofResult.Reason.INVALID_REMOTE_CREDIT)
            }
        }
        if (claim.role == VerifiedCreditRole.TRACK_ARTIST) {
            val releaseTrackCredit = normalizedTrackCredits[recordingId]
            if (releaseTrackCredit != null) {
                if (artist !in releaseTrackCredit) return blocked(MusicBrainzCreditProofResult.Reason.ARTIST_NOT_CREDITED)
                if (releaseTrackCredit.size != 1) return blocked(MusicBrainzCreditProofResult.Reason.MULTIPLE_CREDITED_ARTISTS)
            }
        }
        val rawArtists = when (claim.role) {
            VerifiedCreditRole.TRACK_ARTIST -> recording.artistMbids
            VerifiedCreditRole.ALBUM_ARTIST -> release.albumArtistMbids
        }
        if (rawArtists.size > 16) return blocked(MusicBrainzCreditProofResult.Reason.BUDGET_EXCEEDED)
        if (rawArtists.isEmpty()) return blocked(MusicBrainzCreditProofResult.Reason.MISSING_RESPONSE)
        val artists = rawArtists.mapNotNull(::canonical).toSet()
        if (artists.size != rawArtists.size) {
            return blocked(MusicBrainzCreditProofResult.Reason.INVALID_REMOTE_CREDIT)
        }
        if (artist !in artists) return blocked(MusicBrainzCreditProofResult.Reason.ARTIST_NOT_CREDITED)
        // Multiple credited artists can be a legitimate collaboration; review the
        // joint credit rather than assigning every album to a single performer.
        if (artists.size != 1) return blocked(MusicBrainzCreditProofResult.Reason.MULTIPLE_CREDITED_ARTISTS)
        return MusicBrainzCreditProofResult.CorroboratedForReview(artist, claim.role)
    }
}
