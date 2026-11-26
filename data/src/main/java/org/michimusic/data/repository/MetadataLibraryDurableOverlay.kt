package org.michimusic.data.repository

import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.EmbeddedTagEvidence
import org.michimusic.data.cache.MetadataAppliedTransaction
import kotlin.math.abs

/**
 * Reopen projection for MediaStore rows whose provider metadata still lags the
 * bytes this app committed.
 *
 * MF02: genre is a multivalue tag. The durable scalar is only a compatibility
 * value; the complete ordered list comes from the source-owned
 * [EmbeddedTagEvidence] sidecar through [TrustedGenreEncoding]. Never split a
 * literal semicolon, never infer a singleton from the scalar and never read a
 * foreign owner's sidecar.
 *
 * MF03: a durable row is not proof by itself. Application is bound to one exact
 * [MetadataAppliedTransaction] publication: same owner, track and URI, the
 * committed revision that the row still carries, the candidate length/hash, and
 * a provider observation that still belongs to that publication. A missing,
 * older, newer, foreign or mismatched marker is refused. The provider's modified
 * stamp is only used as a contradiction/freshness fence, never as a content
 * hash: an equal-size edit after publication is refused, and a provider with no
 * trustworthy stamp (for example an insufficient SAF document) is refused
 * instead of applying blindly. No file hashing, library scan or watcher exists
 * here.
 */
object MetadataLibraryDurableOverlay {
    enum class ProviderCapability { MEDIASTORE, SAF_LOCAL, UNSUPPORTED }

    /**
     * One provider announcement for the current row. [modifiedAt] keeps the
     * provider's own units: seconds for MediaStore, milliseconds for SAF.
     * A non-positive value means the provider did not publish a trustworthy
     * stamp; it is never replaced with wall-clock time.
     */
    data class ProviderObservation(
        val sourceId: String,
        val trackId: String,
        val uri: String,
        val sizeBytes: Long,
        val modifiedAt: Long,
        val capability: ProviderCapability,
    )

    enum class Refusal {
        TARGET_MISMATCH,
        NO_DURABLE_REVISION,
        NO_PUBLICATION,
        STALE_PUBLICATION,
        NEWER_PUBLICATION_EXISTS,
        PUBLICATION_MISMATCH,
        DURABLE_CONTENT_CONFLICT,
        PROVIDER_UNAVAILABLE,
        PROVIDER_UNSUPPORTED,
        PROVIDER_SIZE_MISMATCH,
        PROVIDER_STAMP_UNAVAILABLE,
        SOURCE_CHANGED_SINCE_PUBLICATION,
    }

    sealed interface Decision {
        data class Apply(
            val track: Track,
            val genreValues: List<String>,
            /** True only when matching evidence proves the genre tag is absent. */
            val genreAbsenceConfirmed: Boolean,
        ) : Decision

        data class KeepProvider(val reason: Refusal) : Decision
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val TRANSACTION =
        Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private const val NUL = '\u0000'

    /**
     * Applies the policy to a whole owner-scoped snapshot with four indexed
     * reads: durable rows, publications, evidence and provider observations.
     * There is no per-track DAO query and no provider scan.
     */
    fun apply(
        tracks: List<Track>,
        durable: List<CachedTrack>,
        publications: List<MetadataAppliedTransaction>,
        evidence: List<EmbeddedTagEvidence>,
        observations: List<ProviderObservation>,
    ): List<Track> {
        if (tracks.isEmpty() || durable.isEmpty()) return tracks
        val durableById = durable.groupBy { it.id }
        val publicationsByTarget = publications.groupBy { it.sourceId to it.trackId }
        val evidenceByTarget = evidence.associateBy { it.sourceId to it.trackId }
        val observationsByTarget = observations.associateBy { it.sourceId to it.trackId }
        return tracks.map { track ->
            val row = durableById[track.id]?.singleOrNull {
                it.filepath == track.filepath &&
                    MetadataCommittedTrackProjection.sourceMatches(track.sourceId, it.sourceId)
            } ?: return@map track
            val key = row.sourceId to row.id
            when (val decision = decide(
                track = track,
                durable = row,
                publications = publicationsByTarget[key].orEmpty(),
                evidence = evidenceByTarget[key],
                observation = observationsByTarget[key],
            )) {
                is Decision.Apply -> decision.track
                is Decision.KeepProvider -> track
            }
        }
    }

    fun decide(
        track: Track,
        durable: CachedTrack,
        publication: MetadataAppliedTransaction?,
        evidence: EmbeddedTagEvidence?,
        observation: ProviderObservation?,
    ): Decision = decide(
        track = track,
        durable = durable,
        publications = listOfNotNull(publication),
        evidence = evidence,
        observation = observation,
    )

    fun decide(
        track: Track,
        durable: CachedTrack,
        publications: List<MetadataAppliedTransaction>,
        evidence: EmbeddedTagEvidence?,
        observation: ProviderObservation?,
    ): Decision {
        if (!MetadataCommittedTrackProjection.targetMatches(
                track, durable.sourceId, durable.id, durable.filepath,
            )
        ) {
            return Decision.KeepProvider(Refusal.TARGET_MISMATCH)
        }
        if (durable.metadataRevision <= 0L) {
            return Decision.KeepProvider(Refusal.NO_DURABLE_REVISION)
        }
        // A different physical file can reuse a MediaStore ID/URI with the same
        // byte count. Duration is not a cryptographic proof, but a material
        // disagreement is a contradiction that refuses the overlay. Unknown
        // (0) durations never become evidence either way.
        if (!durationAgrees(track.duration, durable.duration)) {
            return Decision.KeepProvider(Refusal.TARGET_MISMATCH)
        }
        val candidates = publications.filter {
            it.sourceId == durable.sourceId && it.trackId == durable.id
        }
        if (candidates.isEmpty()) {
            return Decision.KeepProvider(Refusal.NO_PUBLICATION)
        }
        // A marker for another URI under the same owner/track is not this file's
        // publication. Older markers may remain in the retention window, but an
        // exact single revision must bind the current durable row.
        if (candidates.any { it.uri != durable.filepath }) {
            return Decision.KeepProvider(Refusal.TARGET_MISMATCH)
        }
        if (candidates.any { it.committedRevision > durable.metadataRevision }) {
            return Decision.KeepProvider(Refusal.NEWER_PUBLICATION_EXISTS)
        }
        val exact = candidates.filter { it.committedRevision == durable.metadataRevision }
        if (exact.isEmpty()) {
            return Decision.KeepProvider(Refusal.STALE_PUBLICATION)
        }
        if (exact.size != 1) {
            return Decision.KeepProvider(Refusal.PUBLICATION_MISMATCH)
        }
        val publication = exact.single()
        if (!publicationMatches(publication, durable)) {
            return Decision.KeepProvider(Refusal.PUBLICATION_MISMATCH)
        }
        // A completed verification can only strengthen the publication. A
        // different verified hash is proof that the current bytes are not the
        // committed candidate.
        if (hasVerification(durable)) {
            if (!verificationIsExact(durable) ||
                durable.verifiedSha256 != publication.candidateSha256
            ) {
                return Decision.KeepProvider(Refusal.DURABLE_CONTENT_CONFLICT)
            }
        }

        val observed = observation
            ?: return Decision.KeepProvider(Refusal.PROVIDER_UNAVAILABLE)
        if (observed.sourceId != durable.sourceId ||
            observed.trackId != durable.id || observed.uri != durable.filepath
        ) {
            return Decision.KeepProvider(Refusal.TARGET_MISMATCH)
        }
        if (observed.capability == ProviderCapability.UNSUPPORTED) {
            return Decision.KeepProvider(Refusal.PROVIDER_UNSUPPORTED)
        }
        if (observed.sizeBytes <= 0L ||
            observed.sizeBytes != publication.candidateBytes ||
            track.size != publication.candidateBytes
        ) {
            return Decision.KeepProvider(Refusal.PROVIDER_SIZE_MISMATCH)
        }
        if (observed.modifiedAt <= 0L) {
            return Decision.KeepProvider(Refusal.PROVIDER_STAMP_UNAVAILABLE)
        }
        val modifiedAtMillis = when (observed.capability) {
            ProviderCapability.MEDIASTORE ->
                if (observed.modifiedAt > Long.MAX_VALUE / 1_000L) {
                    return Decision.KeepProvider(Refusal.PROVIDER_STAMP_UNAVAILABLE)
                } else {
                    observed.modifiedAt * 1_000L
                }
            ProviderCapability.SAF_LOCAL -> observed.modifiedAt
            ProviderCapability.UNSUPPORTED ->
                return Decision.KeepProvider(Refusal.PROVIDER_UNSUPPORTED)
        }
        // The provider may lag the committed tags, but it must not announce a
        // file modification later than the publication that is being replayed.
        if (modifiedAtMillis > publication.appliedAt) {
            return Decision.KeepProvider(Refusal.SOURCE_CHANGED_SINCE_PUBLICATION)
        }

        val evidenceUse = trustedEvidence(
            evidence = evidence,
            sourceId = durable.sourceId,
            trackId = durable.id,
            durableGenre = durable.genre,
            expectedBytes = publication.candidateBytes,
            providerModifiedAt = observed.modifiedAt,
        )
        val absent = evidenceUse is EvidenceUse.Absence
        val genreValues = when (evidenceUse) {
            is EvidenceUse.Values -> evidenceUse.values
            EvidenceUse.Absence -> emptyList()
            EvidenceUse.Unavailable -> trustedProviderValues(track, durable.genre)
        }
        val albumChanged = durable.album != track.album ||
            durable.albumArtist != track.albumArtist
        return Decision.Apply(
            track = track.copy(
                title = durable.title,
                artist = durable.artist,
                album = durable.album,
                albumId = if (albumChanged) "" else track.albumId,
                albumArtist = durable.albumArtist,
                genre = durable.genre,
                genreValues = genreValues,
                trackNumber = durable.trackNumber,
                year = durable.year,
                size = publication.candidateBytes,
                contentHash = publication.candidateSha256,
            ),
            genreValues = genreValues,
            genreAbsenceConfirmed = absent,
        )
    }

    /**
     * MF02 SAF/durable reopen: the rows are already the committed owner, so no
     * provider overlay is replayed. Attach the complete ordered genre list only
     * when a source-owned sidecar still matches the durable row's size, parser
     * generation and compatibility scalar. Otherwise the scalar stays honest
     * and no singleton is invented.
     */
    fun attachDurableGenres(
        tracks: List<Track>,
        durable: List<CachedTrack>,
        evidence: List<EmbeddedTagEvidence>,
    ): List<Track> {
        if (tracks.isEmpty() || durable.isEmpty() || evidence.isEmpty()) return tracks
        val rows = durable.associateBy { it.sourceId to it.id }
        val sidecars = evidence.associateBy { it.sourceId to it.trackId }
        return tracks.map { track ->
            val row = rows[track.sourceId to track.id] ?: return@map track
            if (row.filepath != track.filepath || row.genre != track.genre) return@map track
            val sidecar = sidecars[row.sourceId to row.id] ?: return@map track
            when (val use = trustedEvidence(
                evidence = sidecar,
                sourceId = row.sourceId,
                trackId = row.id,
                durableGenre = row.genre,
                expectedBytes = row.size,
                providerModifiedAt = null,
            )) {
                is EvidenceUse.Values -> track.copy(genreValues = use.values)
                EvidenceUse.Absence -> track.copy(genreValues = emptyList())
                EvidenceUse.Unavailable -> track
            }
        }
    }

    private fun durationAgrees(observedMs: Long, durableMs: Long): Boolean {
        if (observedMs <= 0L || durableMs <= 0L) return true
        if (abs(observedMs - durableMs) <= 1_000L) return true
        // Historical rows may have stored a short duration in seconds.
        return (durableMs <= 86_400L &&
            abs(durableMs * 1_000L - observedMs) <= 1_000L) ||
            (observedMs <= 86_400L &&
                abs(observedMs * 1_000L - durableMs) <= 1_000L)
    }

    private fun publicationMatches(
        publication: MetadataAppliedTransaction,
        durable: CachedTrack,
    ): Boolean {
        if (!publication.transactionId.matches(TRANSACTION) ||
            !publication.candidateSha256.matches(SHA256) ||
            publication.candidateBytes <= 0L ||
            publication.appliedAt <= 0L ||
            publication.originalRevision < 0L ||
            publication.originalRevision == Long.MAX_VALUE
        ) {
            return false
        }
        return publication.sourceId == durable.sourceId &&
            publication.trackId == durable.id &&
            publication.uri == durable.filepath &&
            publication.originalRevision + 1L == publication.committedRevision &&
            publication.committedRevision == durable.metadataRevision &&
            publication.candidateBytes == durable.size
    }

    private fun hasVerification(row: CachedTrack): Boolean =
        row.verifiedAt > 0L || row.verifiedSize > 0L ||
            row.verifiedSha256.isNotEmpty() || row.verifiedAlgorithm.isNotEmpty()

    private fun verificationIsExact(row: CachedTrack): Boolean =
        row.verifiedAt > 0L && row.verifiedSize == row.size &&
            row.verifiedAlgorithm.lowercase() == "sha256" &&
            row.verifiedSha256.matches(SHA256)

    private sealed interface EvidenceUse {
        data class Values(val values: List<String>) : EvidenceUse
        data object Absence : EvidenceUse
        data object Unavailable : EvidenceUse
    }

    private fun trustedEvidence(
        evidence: EmbeddedTagEvidence?,
        sourceId: String,
        trackId: String,
        durableGenre: String,
        expectedBytes: Long,
        providerModifiedAt: Long?,
    ): EvidenceUse {
        if (evidence == null) return EvidenceUse.Unavailable
        if (evidence.sourceId != sourceId || evidence.trackId != trackId) {
            return EvidenceUse.Unavailable
        }
        if (expectedBytes <= 0L ||
            evidence.parserVersion != EmbeddedTagEvidence.CURRENT_PARSER_VERSION ||
            evidence.sizeBytes != expectedBytes ||
            evidence.modifiedAt <= 0L ||
            (providerModifiedAt != null &&
                (providerModifiedAt <= 0L || evidence.modifiedAt != providerModifiedAt))
        ) {
            return EvidenceUse.Unavailable
        }
        // The merge policy preserves the literal compatibility value; evidence
        // that would rewrite the committed scalar is not the same revision.
        val scalar = GenreMetadataMerge.choose(evidence.genre, durableGenre, null)
        if (scalar != durableGenre) return EvidenceUse.Unavailable
        if (evidence.genreValues.isEmpty()) {
            return if (durableGenre.isEmpty() && evidence.genre.isEmpty()) {
                EvidenceUse.Absence
            } else {
                EvidenceUse.Unavailable
            }
        }
        val values = TrustedGenreEncoding.decode(evidence.genre, evidence.genreValues)
        if (values.isEmpty()) return EvidenceUse.Unavailable
        // Never invent a singleton or split a literal semicolon: the complete
        // ordered set must reproduce the durable compatibility scalar exactly.
        if (values.joinToString("; ") != durableGenre) return EvidenceUse.Unavailable
        return EvidenceUse.Values(values)
    }

    /**
     * A provider row read in the same snapshot may already carry a trusted
     * ordered list. It is reused only when it describes exactly the durable
     * scalar and passes the same lossless decoder; otherwise the honest answer
     * is the legacy scalar with no structured list.
     */
    private fun trustedProviderValues(track: Track, durableGenre: String): List<String> {
        if (track.genre != durableGenre || track.genreValues.isEmpty()) return emptyList()
        val values = track.genreValues
        if (values.size > 32 || values.sumOf(String::length) > 8_192) return emptyList()
        if (values.any { value ->
                value.isBlank() || value.length > 2_048 ||
                    value != value.trim() || '\uFFFD' in value ||
                    NUL in value || value.any(Char::isISOControl)
            }
        ) {
            return emptyList()
        }
        val decoded = TrustedGenreEncoding.decode(track.genre, values.joinToString("\u0000"))
        return if (decoded == values) decoded else emptyList()
    }
}
