package org.michimusic.core.metadata

/** ME-03: conservative capability vocabulary, not a runtime certification claim. */
enum class MetadataAudioContainer {
    FLAC, ID3_V23, ID3_V24, OGG_VORBIS, OGG_OPUS, MP4_M4A, WAV, AIFF, WAVPACK, APE, UNKNOWN
}

enum class MetadataDocumentProvider { MEDIASTORE, SAF, REMOTE, UNKNOWN }

enum class MetadataFieldCapability {
    /** No reliable reader evidence for this field on this receipt. */
    NOT_VERIFIED,
    /** May be modified in a draft; no binary transformer prepared. */
    PREVIEW_ONLY,
    /** A conservative off-device transformer can create a SEPARATE staged candidate. */
    STAGING_CANDIDATE,
}

enum class MetadataCapabilityReason {
    UNVERIFIED_RECEIPT, FIELD_NOT_VERIFIED, NOT_LOCAL_DOCUMENT, UNRECOGNIZED_CONTAINER,
    PROVIDER_NOT_PROVEN, FIELD_NOT_SUPPORTED_IN_STAGER, MULTIVALUE_NOT_REPRESENTABLE,
    EXPLICIT_FORMAT_SNIFF_REQUIRED, EMPTY_DIFF, WRITE_NOT_CERTIFIED,
}

data class MetadataFieldAssessment(
    val field: MetadataField,
    val capability: MetadataFieldCapability,
    val reasons: Set<MetadataCapabilityReason> = emptySet(),
)

data class MetadataCapabilityReport(
    val container: MetadataAudioContainer,
    val provider: MetadataDocumentProvider,
    val fields: List<MetadataFieldAssessment>,
    /** Always false. The user + Android permission + journal + recovery are NOT represented here. */
    val canCommitToSource: Boolean = false,
) {
    val stagedCandidateFields: Set<MetadataField>
        get() = fields.filter { it.capability == MetadataFieldCapability.STAGING_CANDIDATE }
            .mapTo(linkedSetOf()) { it.field }
}

/**
 * The container input must be determined by inspecting CURRENT bytes by the future
 * Android host. The registry never trusts a filename extension, MIME or `Track.format`
 * as evidence sufficient for mutation. Even STAGING_CANDIDATE is not a write grant.
 */
object MetadataCapabilityRegistry {
    val flacStageFields: Set<MetadataField> = setOf(
        MetadataField.TITLE, MetadataField.ARTIST, MetadataField.ALBUM,
        MetadataField.ALBUM_ARTIST, MetadataField.GENRE, MetadataField.COMPOSER,
        MetadataField.RELEASE_DATE,
        MetadataField.TRACK_NUMBER, MetadataField.TRACK_TOTAL,
        MetadataField.DISC_NUMBER, MetadataField.DISC_TOTAL,
        MetadataField.LABEL, MetadataField.CATALOG_NUMBER, MetadataField.ISRC,
        MetadataField.BPM, MetadataField.MUSICAL_KEY,
        MetadataField.ALBUM_SUBTITLE,
        MetadataField.ORIGINAL_ARTIST,
        MetadataField.ORCHESTRA,
        MetadataField.ENSEMBLE,
        MetadataField.SOLOIST,
        MetadataField.INSTRUMENT,
        MetadataField.MASTERING_ENGINEER,
        MetadataField.EXECUTIVE_PRODUCER,
        MetadataField.WORK_NUMBER,
        MetadataField.OPUS_NUMBER,
        MetadataField.MOVEMENT_NUMBER,
        MetadataField.MOVEMENT_TOTAL,
        MetadataField.RECORDING_DATE,
        MetadataField.EDITION,
        MetadataField.RELEASE_TYPE,
        MetadataField.RELEASE_STATUS,
        MetadataField.MEDIA_FORMAT,
        MetadataField.COMPILATION,
        MetadataField.SCRIPT,
        MetadataField.WORK,
        MetadataField.MOVEMENT,
        MetadataField.ARRANGER,
        MetadataField.LYRICIST,
        MetadataField.CONDUCTOR,
        MetadataField.PERFORMER,
        MetadataField.PRODUCER,
        MetadataField.COMMENT,
        MetadataField.COPYRIGHT,
        MetadataField.LANGUAGE,
        MetadataField.PUBLISHER,
        MetadataField.GROUPING,
        MetadataField.MOOD,
        MetadataField.LYRICS,
    ) + MetadataMusicBrainzTagMap.flacKeys.keys
    val id3StageFields: Set<MetadataField> = setOf(
        MetadataField.TITLE, MetadataField.ARTIST, MetadataField.ALBUM,
        MetadataField.ALBUM_ARTIST, MetadataField.GENRE, MetadataField.COMPOSER,
        MetadataField.SUBTITLE,
        MetadataField.LABEL,
        MetadataField.ISRC,
        MetadataField.BPM,
        MetadataField.MUSICAL_KEY,
        MetadataField.GROUPING,
        MetadataField.COPYRIGHT,
        MetadataField.LANGUAGE,
        MetadataField.ORIGINAL_ARTIST,
        MetadataField.LYRICIST,
        MetadataField.CONDUCTOR,
        MetadataField.REMIXER,
        MetadataField.RELEASE_DATE,
        MetadataField.TRACK_NUMBER, MetadataField.TRACK_TOTAL,
        MetadataField.DISC_NUMBER, MetadataField.DISC_TOTAL,
    ) + MetadataMusicBrainzTagMap.id3Fields + MetadataId3DescribedTagCodec.fields
    /** APEv2 is limited to a proven, footer-only private candidate. No provider write. */
    val apeStageFields: Set<MetadataField> = MetadataField.entries
        .filterTo(linkedSetOf()) { MetadataApeStageRewriter.supports(it) }
    val wavStageFields: Set<MetadataField> = MetadataRiffStageRewriter.supportedFields
    val aiffStageFields: Set<MetadataField> = MetadataAiffStageRewriter.supportedFields
    val oggStageFields: Set<MetadataField> = MetadataOggStageRewriter.supportedFields
    val mp4StageFields: Set<MetadataField> = MetadataMp4StageRewriter.supportedFields + setOf(
        MetadataField.TRACK_NUMBER, MetadataField.TRACK_TOTAL,
        MetadataField.DISC_NUMBER, MetadataField.DISC_TOTAL,
    )
    // All engines only produce PRIVATE staged cache files and never gain write grants.
    // The genre-only existing writer is NOT promoted to a generic writer.

    fun assess(snapshot: MetadataSnapshot, container: MetadataAudioContainer,
               provider: MetadataDocumentProvider, formatSniffed: Boolean,
               fields: Set<MetadataField> = MetadataField.entries.toSet()): MetadataCapabilityReport {
        val assessments = fields.map { field ->
            val reasons = linkedSetOf<MetadataCapabilityReason>()
            if (!snapshot.tagsVerified) reasons += MetadataCapabilityReason.UNVERIFIED_RECEIPT
            if (field !in snapshot.verifiedFields) reasons += MetadataCapabilityReason.FIELD_NOT_VERIFIED
            if (!snapshot.target.uri.startsWith("content://") || provider == MetadataDocumentProvider.REMOTE)
                reasons += MetadataCapabilityReason.NOT_LOCAL_DOCUMENT
            if (provider == MetadataDocumentProvider.UNKNOWN)
                reasons += MetadataCapabilityReason.PROVIDER_NOT_PROVEN
            if (container == MetadataAudioContainer.UNKNOWN)
                reasons += MetadataCapabilityReason.UNRECOGNIZED_CONTAINER
            if (!formatSniffed) reasons += MetadataCapabilityReason.EXPLICIT_FORMAT_SNIFF_REQUIRED
            val stageSupported = when (container) {
                MetadataAudioContainer.FLAC -> field in flacStageFields
                MetadataAudioContainer.ID3_V23, MetadataAudioContainer.ID3_V24 -> field in id3StageFields
                MetadataAudioContainer.WAVPACK, MetadataAudioContainer.APE -> field in apeStageFields
                MetadataAudioContainer.WAV -> field in wavStageFields
                MetadataAudioContainer.AIFF -> field in aiffStageFields
                MetadataAudioContainer.OGG_OPUS, MetadataAudioContainer.OGG_VORBIS -> field in oggStageFields
                MetadataAudioContainer.MP4_M4A -> field in mp4StageFields
                else -> false
            }
            if (!stageSupported) reasons += MetadataCapabilityReason.FIELD_NOT_SUPPORTED_IN_STAGER
            val capability = when {
                MetadataCapabilityReason.UNVERIFIED_RECEIPT in reasons ||
                    MetadataCapabilityReason.FIELD_NOT_VERIFIED in reasons ->
                    MetadataFieldCapability.NOT_VERIFIED
                stageSupported && reasons.isEmpty() -> MetadataFieldCapability.STAGING_CANDIDATE
                else -> MetadataFieldCapability.PREVIEW_ONLY
            }
            MetadataFieldAssessment(field, capability, reasons)
        }
        return MetadataCapabilityReport(container, provider, assessments)
    }

    /** Strict preflight for pure preview results, not provider authorization. */
    sealed interface StageReview {
        data class Candidate(val review: MetadataReview.Ready, val report: MetadataCapabilityReport) : StageReview
        data class Blocked(val reasons: Set<MetadataCapabilityReason>) : StageReview
    }

    fun reviewStagingCandidate(
        review: MetadataReview, container: MetadataAudioContainer,
        provider: MetadataDocumentProvider, formatSniffed: Boolean,
    ): StageReview {
        if (review !is MetadataReview.Ready) return StageReview.Blocked(
            setOf(if (review is MetadataReview.Blocked) MetadataCapabilityReason.UNVERIFIED_RECEIPT
                  else MetadataCapabilityReason.EMPTY_DIFF))
        if (MetadataReviewIntegrityPolicy.inspect(review) !=
            MetadataReviewIntegrityPolicy.Verdict.Exact)
            return StageReview.Blocked(setOf(MetadataCapabilityReason.UNVERIFIED_RECEIPT))
        val fields = review.diffs.mapTo(linkedSetOf()) { it.field }
        val report = assess(review.original, container, provider, formatSniffed, fields)
        val failures = report.fields.flatMapTo(linkedSetOf()) { it.reasons }
        if (failures.isNotEmpty()) return StageReview.Blocked(failures)
        if (container == MetadataAudioContainer.WAV && review.diffs.any { diff ->
                // The RIFF private transformer edits existing, unique ASCII
                // INFO values only; neither INSERT nor CLEAR is supported.
                diff.before.size != 1 || diff.after.size != 1 ||
                    diff.after.singleOrNull()?.any { it.code !in 0x20..0x7e } != false
            }) return StageReview.Blocked(setOf(MetadataCapabilityReason.MULTIVALUE_NOT_REPRESENTABLE))
        if (container in setOf(MetadataAudioContainer.OGG_OPUS,
                MetadataAudioContainer.OGG_VORBIS, MetadataAudioContainer.MP4_M4A,
                MetadataAudioContainer.AIFF) && review.diffs.any { diff ->
                diff.before.size != 1 || diff.after.size != 1
            }) return StageReview.Blocked(setOf(MetadataCapabilityReason.MULTIVALUE_NOT_REPRESENTABLE))
        if (review.diffs.any { diff ->
                // ID3v2.4 has a canonical NUL-separated text-list encoding.
                // ID3v2.3 remains single-valued; TXXX/COMM/USLT identity
                // semantics are not modeled as plain multivalue text frames.
                diff.after.size > 1 && (
                    !diff.field.multiple ||
                    container == MetadataAudioContainer.WAV ||
                    container == MetadataAudioContainer.AIFF ||
                    container == MetadataAudioContainer.MP4_M4A ||
                    container == MetadataAudioContainer.OGG_OPUS ||
                    container == MetadataAudioContainer.OGG_VORBIS ||
                    container == MetadataAudioContainer.ID3_V23 ||
                    (container == MetadataAudioContainer.ID3_V24 &&
                        MetadataId3StageRewriter.preservationFrameId(diff.field) == null))
            })
            return StageReview.Blocked(setOf(MetadataCapabilityReason.MULTIVALUE_NOT_REPRESENTABLE))
        return StageReview.Candidate(review, report)
    }
}
