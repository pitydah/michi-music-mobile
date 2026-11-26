package org.michimusic.core.metadata

/**
 * ME-13: an exact, test-backed write capability. A profile is never inferred
 * from a file extension, MIME string or filename prefix; callers must match the
 * observed provider, container/revision and field set.
 */
data class CertifiedMetadataWriteProfile(
    val id: String,
    val provider: MetadataProviderRecoveryProfile.Kind,
    val containers: Set<MetadataAudioContainer>,
    val tagRevisions: Set<Int>,
    val supportedFields: Set<MetadataField>,
    val permitsArtwork: Boolean,
    val permitsMultiValue: Set<MetadataField>,
    val capabilityEvidenceId: String,
    val supportedResourceOperations: Set<MetadataResourceOperation> = emptySet(),
) {
    fun supports(container: MetadataAudioContainer, revision: Int, fields: Set<MetadataField>,
        multiValueFields: Set<MetadataField>, artworkChanged: Boolean): Boolean {
        val revisionMatches = when (container) {
            MetadataAudioContainer.ID3_V23 -> revision == 3
            MetadataAudioContainer.ID3_V24 -> revision == 4
            else -> revision in tagRevisions
        }
        return container in containers && revisionMatches &&
            fields.all { it in supportedFields } &&
            multiValueFields.all { it in permitsMultiValue } &&
            (!artworkChanged || permitsArtwork)
    }

    fun supports(resources: List<MetadataCommitIntentManifest.ResourceChange>): Boolean =
        resources.all { MetadataResourceOperation.of(it) in supportedResourceOperations }
}

enum class MetadataResourceOperation {
    ID3_APIC_REPLACE,
    FLAC_PICTURE_REPLACE,
    ID3_DESCRIBED_TEXT_REPLACE,
    ID3_DESCRIBED_TEXT_CLEAR,
    ID3_APIC_ADD,
    ID3_APIC_REMOVE,
    FLAC_PICTURE_ADD,
    FLAC_PICTURE_REMOVE,
    MP4_COVR_REPLACE,
    MP4_COVR_ADD,
    MP4_COVR_REMOVE;

    companion object {
        fun of(change: MetadataCommitIntentManifest.ResourceChange): MetadataResourceOperation =
            when (change) {
                is MetadataCommitIntentManifest.ResourceChange.PictureReplacement ->
                    when (change.format) {
                        MetadataArtworkEvidence.Format.ID3_APIC -> ID3_APIC_REPLACE
                        MetadataArtworkEvidence.Format.FLAC_PICTURE -> FLAC_PICTURE_REPLACE
                        MetadataArtworkEvidence.Format.MP4_COVR -> MP4_COVR_REPLACE
                    }
                is MetadataCommitIntentManifest.ResourceChange.PictureAdd ->
                    when (change.format) {
                        MetadataArtworkEvidence.Format.ID3_APIC -> ID3_APIC_ADD
                        MetadataArtworkEvidence.Format.FLAC_PICTURE -> FLAC_PICTURE_ADD
                        MetadataArtworkEvidence.Format.MP4_COVR -> MP4_COVR_ADD
                    }
                is MetadataCommitIntentManifest.ResourceChange.PictureRemove ->
                    when (change.format) {
                        MetadataArtworkEvidence.Format.ID3_APIC -> ID3_APIC_REMOVE
                        MetadataArtworkEvidence.Format.FLAC_PICTURE -> FLAC_PICTURE_REMOVE
                        MetadataArtworkEvidence.Format.MP4_COVR -> MP4_COVR_REMOVE
                    }
                is MetadataCommitIntentManifest.ResourceChange.DescribedText ->
                    if (change.afterCount == 0) ID3_DESCRIBED_TEXT_CLEAR
                    else ID3_DESCRIBED_TEXT_REPLACE
            }
    }
}

sealed interface SaveEligibility {
    data class Available(val profile: CertifiedMetadataWriteProfile) : SaveEligibility
    data class Blocked(val reason: SaveBlock) : SaveEligibility
}

enum class SaveBlock {
    UNVERIFIED_TAGS, UNSUPPORTED_PROVIDER, UNSUPPORTED_CONTAINER,
    UNSUPPORTED_TAG_REVISION, UNSUPPORTED_FIELDS, UNSUPPORTED_MULTIVALUE,
    ARTWORK_NOT_CERTIFIED, NO_EXACT_GRANT, SOURCE_CHANGED, PLAYBACK_ACTIVE,
    RECOVERY_PENDING, NO_CERTIFIED_PROFILE, INTEGRITY_NOT_VERIFIED
}

/**
 * MP3 / ID3v2.3-v2.4 text and representable multivalue on the local MediaStore
 * provider. Scope expansion is backed by cold-reader support for every field:
 * track/disc pairs, version-specific dates, credits and described canonical text.
 * Multivalue is limited to fields the cold parser accepts as ordered lists.
 */
private val id3ProfileFields: Set<MetadataField> = linkedSetOf(
    MetadataField.TITLE, MetadataField.ARTIST, MetadataField.ALBUM,
    MetadataField.ALBUM_ARTIST, MetadataField.GENRE, MetadataField.COMPOSER,
    MetadataField.SUBTITLE, MetadataField.LABEL, MetadataField.ISRC,
    MetadataField.BPM, MetadataField.MUSICAL_KEY, MetadataField.GROUPING,
    MetadataField.COPYRIGHT, MetadataField.LANGUAGE, MetadataField.ORIGINAL_ARTIST,
    MetadataField.LYRICIST, MetadataField.CONDUCTOR, MetadataField.REMIXER,
    MetadataField.RELEASE_DATE, MetadataField.TRACK_NUMBER,
    MetadataField.TRACK_TOTAL, MetadataField.DISC_NUMBER, MetadataField.DISC_TOTAL,
    MetadataField.COMMENT, MetadataField.LYRICS,
) + MetadataMusicBrainzTagMap.id3Fields

private val id3ProfileMultiValue: Set<MetadataField> = setOf(
    MetadataField.ARTIST, MetadataField.ALBUM_ARTIST, MetadataField.GENRE,
    MetadataField.COMPOSER, MetadataField.ORIGINAL_ARTIST,
    MetadataField.LYRICIST, MetadataField.CONDUCTOR,
)

object MediaStoreMp3TextProfile {
    const val ID = "MS_LOCAL_MP3_ID3_V23V24_TEXT_RESOURCES_V3"
    const val EVIDENCE_ID = "ME16_MEDIASTORE_MP3_ID3_RESOURCES_V3"

    val profile = CertifiedMetadataWriteProfile(
        id = ID,
        provider = MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO,
        containers = setOf(MetadataAudioContainer.ID3_V23, MetadataAudioContainer.ID3_V24),
        tagRevisions = setOf(3, 4),
        supportedFields = id3ProfileFields,
        permitsArtwork = false,
        permitsMultiValue = id3ProfileMultiValue,
        capabilityEvidenceId = EVIDENCE_ID,
        supportedResourceOperations = setOf(
            MetadataResourceOperation.ID3_APIC_REPLACE,
            MetadataResourceOperation.ID3_APIC_ADD,
            MetadataResourceOperation.ID3_APIC_REMOVE,
            MetadataResourceOperation.ID3_DESCRIBED_TEXT_REPLACE,
            MetadataResourceOperation.ID3_DESCRIBED_TEXT_CLEAR,
        ),
    )
}

private val flacProfileMultiValue: Set<MetadataField> = setOf(
    MetadataField.ARTIST, MetadataField.ALBUM_ARTIST,
    MetadataField.GENRE, MetadataField.COMPOSER,
    MetadataField.ORCHESTRA, MetadataField.ENSEMBLE,
    MetadataField.SOLOIST, MetadataField.INSTRUMENT,
    MetadataField.ORIGINAL_ARTIST, MetadataField.LYRICIST,
    MetadataField.ARRANGER, MetadataField.PERFORMER,
    MetadataField.CONDUCTOR, MetadataField.PRODUCER,
    MetadataField.MUSICBRAINZ_ARTIST_ID, MetadataField.MUSICBRAINZ_LABEL_ID,
).intersect(MetadataCapabilityRegistry.flacStageFields)

/**
 * FLAC / Vorbis Comment title on an app-owned local MediaStore item.
 *
 * Keep this deliberately narrow: broader scalar staging exists, but production
 * authorization expands only after the complete provider/readback/Room/recovery
 * path has independent evidence for each operation.
 */
object MediaStoreFlacTextProfile {
    const val ID = "MS_LOCAL_FLAC_VORBIS_TEXT_PICTURE_V2"
    const val EVIDENCE_ID = "ME16_MEDIASTORE_FLAC_TEXT_PICTURE_V2"
    val profile = CertifiedMetadataWriteProfile(
        id = ID,
        provider = MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO,
        containers = setOf(MetadataAudioContainer.FLAC),
        tagRevisions = setOf(1),
        supportedFields = MetadataCapabilityRegistry.flacStageFields,
        permitsArtwork = false,
        permitsMultiValue = flacProfileMultiValue,
        capabilityEvidenceId = EVIDENCE_ID,
        supportedResourceOperations = setOf(
            MetadataResourceOperation.FLAC_PICTURE_REPLACE,
            MetadataResourceOperation.FLAC_PICTURE_ADD,
            MetadataResourceOperation.FLAC_PICTURE_REMOVE,
        ),
    )
}

private val m4aProfileFields: Set<MetadataField> = setOf(
    MetadataField.TITLE, MetadataField.ARTIST, MetadataField.ALBUM,
    MetadataField.ALBUM_ARTIST, MetadataField.GENRE,
    MetadataField.COMPOSER, MetadataField.RELEASE_DATE,
    MetadataField.COMMENT, MetadataField.LYRICS,
    MetadataField.GROUPING,
    MetadataField.TRACK_NUMBER, MetadataField.TRACK_TOTAL,
    MetadataField.DISC_NUMBER, MetadataField.DISC_TOTAL,
)

/**
 * Audio-only M4A text rewrite through the pinned JAudiotagger engine.
 *
 * Evidence covers API 36 MediaStore mutation, exact AAC `mdat` preservation,
 * unrelated standard atom preservation, add/clear of representable text,
 * track/disc pairs, cold readback and atomic Room publication.
 */
object MediaStoreM4aTextProfile {
    const val ID = "MS_LOCAL_M4A_AUDIO_ONLY_TITLE_V1"
    const val EVIDENCE_ID = "ME17_MEDIASTORE_M4A_AUDIO_ONLY_TITLE_V1"
    val profile = CertifiedMetadataWriteProfile(
        id = ID,
        provider = MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO,
        containers = setOf(MetadataAudioContainer.MP4_M4A),
        tagRevisions = setOf(1),
        supportedFields = m4aProfileFields,
        permitsArtwork = false,
        permitsMultiValue = emptySet(),
        capabilityEvidenceId = EVIDENCE_ID,
        supportedResourceOperations = setOf(
            MetadataResourceOperation.MP4_COVR_REPLACE,
            MetadataResourceOperation.MP4_COVR_ADD,
            MetadataResourceOperation.MP4_COVR_REMOVE,
        ),
    )
}

/**
 * MP3 text and representable multivalue rewrite on a local SAF document with an
 * exact persisted write grant. Evidence is deliberately separate from MediaStore.
 */
object SafLocalMp3TitleProfile {
    const val ID = "SAF_LOCAL_MP3_ID3_V23V24_TITLE_V1"
    const val EVIDENCE_ID = "ME18_SAF_DOCUMENT_MP3_TITLE_V1"
    val profile = CertifiedMetadataWriteProfile(
        id = ID,
        provider = MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT,
        containers = setOf(MetadataAudioContainer.ID3_V23, MetadataAudioContainer.ID3_V24),
        tagRevisions = setOf(3, 4),
        supportedFields = id3ProfileFields,
        permitsArtwork = false,
        permitsMultiValue = id3ProfileMultiValue,
        capabilityEvidenceId = EVIDENCE_ID,
    )
}

/**
 * FLAC text and representable multivalue on a local SAF document. Resource
 * operations remain closed until their own persisted-grant evidence exists.
 */
object SafLocalFlacTextProfile {
    const val ID = "SAF_LOCAL_FLAC_VORBIS_TEXT_V1"
    const val EVIDENCE_ID = "ME19_SAF_DOCUMENT_FLAC_TEXT_V1"
    val profile = CertifiedMetadataWriteProfile(
        id = ID,
        provider = MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT,
        containers = setOf(MetadataAudioContainer.FLAC),
        tagRevisions = setOf(1),
        supportedFields = MetadataCapabilityRegistry.flacStageFields,
        permitsArtwork = false,
        permitsMultiValue = flacProfileMultiValue,
        capabilityEvidenceId = EVIDENCE_ID,
    )
}

/**
 * Audio-only M4A text and pairs on a local SAF document. Video and resource
 * operations remain closed until their own persisted-grant evidence exists.
 */
object SafLocalM4aTextProfile {
    const val ID = "SAF_LOCAL_M4A_AUDIO_ONLY_TEXT_V1"
    const val EVIDENCE_ID = "ME19_SAF_DOCUMENT_M4A_AUDIO_ONLY_TEXT_V1"
    val profile = CertifiedMetadataWriteProfile(
        id = ID,
        provider = MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT,
        containers = setOf(MetadataAudioContainer.MP4_M4A),
        tagRevisions = setOf(1),
        supportedFields = m4aProfileFields,
        permitsArtwork = false,
        permitsMultiValue = emptySet(),
        capabilityEvidenceId = EVIDENCE_ID,
    )
}

object MetadataWriteProfileRegistry {
    fun byId(id: String): CertifiedMetadataWriteProfile? = when (id) {
        MediaStoreMp3TextProfile.ID -> MediaStoreMp3TextProfile.profile
        MediaStoreFlacTextProfile.ID -> MediaStoreFlacTextProfile.profile
        MediaStoreM4aTextProfile.ID -> MediaStoreM4aTextProfile.profile
        SafLocalMp3TitleProfile.ID -> SafLocalMp3TitleProfile.profile
        SafLocalFlacTextProfile.ID -> SafLocalFlacTextProfile.profile
        SafLocalM4aTextProfile.ID -> SafLocalM4aTextProfile.profile
        else -> null
    }
    fun forContainer(container: MetadataAudioContainer): CertifiedMetadataWriteProfile? =
        forProviderAndContainer(
            MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO,
            container,
        )

    fun forProviderAndContainer(
        provider: MetadataProviderRecoveryProfile.Kind,
        container: MetadataAudioContainer,
    ): CertifiedMetadataWriteProfile? =
        when (provider) {
            MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO -> when (container) {
                MetadataAudioContainer.ID3_V23, MetadataAudioContainer.ID3_V24 ->
                    MediaStoreMp3TextProfile.profile
                MetadataAudioContainer.FLAC -> MediaStoreFlacTextProfile.profile
                MetadataAudioContainer.MP4_M4A -> MediaStoreM4aTextProfile.profile
                else -> null
            }
            MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT -> when (container) {
                MetadataAudioContainer.ID3_V23, MetadataAudioContainer.ID3_V24 ->
                    SafLocalMp3TitleProfile.profile
                MetadataAudioContainer.FLAC -> SafLocalFlacTextProfile.profile
                MetadataAudioContainer.MP4_M4A -> SafLocalM4aTextProfile.profile
                else -> null
            }
            MetadataProviderRecoveryProfile.Kind.UNKNOWN_OR_REMOTE -> null
        }

    fun forDocumentProviderAndContainer(
        provider: MetadataDocumentProvider,
        container: MetadataAudioContainer,
    ): CertifiedMetadataWriteProfile? = forProviderAndContainer(
        when (provider) {
            MetadataDocumentProvider.MEDIASTORE ->
                MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO
            MetadataDocumentProvider.SAF ->
                MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT
            MetadataDocumentProvider.REMOTE, MetadataDocumentProvider.UNKNOWN ->
                MetadataProviderRecoveryProfile.Kind.UNKNOWN_OR_REMOTE
        },
        container,
    )
}
