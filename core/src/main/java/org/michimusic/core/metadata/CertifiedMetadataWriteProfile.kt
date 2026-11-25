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
    ID3_DESCRIBED_TEXT_CLEAR;

    companion object {
        fun of(change: MetadataCommitIntentManifest.ResourceChange): MetadataResourceOperation =
            when (change) {
                is MetadataCommitIntentManifest.ResourceChange.PictureReplacement ->
                    when (change.format) {
                        MetadataArtworkEvidence.Format.ID3_APIC -> ID3_APIC_REPLACE
                        MetadataArtworkEvidence.Format.FLAC_PICTURE -> FLAC_PICTURE_REPLACE
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
 * MP3 / ID3v2.3-v2.4 on the local MediaStore provider, single-value text only.
 * Evidence: ME-13 disposable MediaStore fixtures (TITLE/ARTIST/ALBUM/GENRE),
 * cold reread and audio payload equality.
 */
object MediaStoreMp3TextProfile {
    const val ID = "MS_LOCAL_MP3_ID3_V23V24_TEXT_RESOURCES_V3"
    const val EVIDENCE_ID = "ME16_MEDIASTORE_MP3_ID3_RESOURCES_V3"

    val profile = CertifiedMetadataWriteProfile(
        id = ID,
        provider = MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO,
        containers = setOf(MetadataAudioContainer.ID3_V23, MetadataAudioContainer.ID3_V24),
        tagRevisions = setOf(3, 4),
        supportedFields = setOf(
            MetadataField.TITLE, MetadataField.ARTIST,
            MetadataField.ALBUM, MetadataField.GENRE,
        ),
        permitsArtwork = false,
        permitsMultiValue = emptySet(),
        capabilityEvidenceId = EVIDENCE_ID,
        supportedResourceOperations = setOf(
            MetadataResourceOperation.ID3_APIC_REPLACE,
            MetadataResourceOperation.ID3_DESCRIBED_TEXT_REPLACE,
            MetadataResourceOperation.ID3_DESCRIBED_TEXT_CLEAR,
        ),
    )
}

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
        supportedFields = setOf(MetadataField.TITLE),
        permitsArtwork = false,
        permitsMultiValue = emptySet(),
        capabilityEvidenceId = EVIDENCE_ID,
        supportedResourceOperations = setOf(
            MetadataResourceOperation.FLAC_PICTURE_REPLACE,
        ),
    )
}

/**
 * Audio-only M4A title rewrite through the pinned JAudiotagger engine.
 *
 * Evidence covers API 36 MediaStore mutation, exact AAC `mdat` preservation,
 * unrelated standard atom preservation, cold readback and atomic Room publish.
 */
object MediaStoreM4aTextProfile {
    const val ID = "MS_LOCAL_M4A_AUDIO_ONLY_TITLE_V1"
    const val EVIDENCE_ID = "ME17_MEDIASTORE_M4A_AUDIO_ONLY_TITLE_V1"
    val profile = CertifiedMetadataWriteProfile(
        id = ID,
        provider = MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO,
        containers = setOf(MetadataAudioContainer.MP4_M4A),
        tagRevisions = setOf(1),
        supportedFields = setOf(MetadataField.TITLE),
        permitsArtwork = false,
        permitsMultiValue = emptySet(),
        capabilityEvidenceId = EVIDENCE_ID,
    )
}

/**
 * MP3 title rewrite on a local SAF document with an exact persisted write
 * grant. This evidence is deliberately separate from MediaStore authority.
 */
object SafLocalMp3TitleProfile {
    const val ID = "SAF_LOCAL_MP3_ID3_V23V24_TITLE_V1"
    const val EVIDENCE_ID = "ME18_SAF_DOCUMENT_MP3_TITLE_V1"
    val profile = CertifiedMetadataWriteProfile(
        id = ID,
        provider = MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT,
        containers = setOf(MetadataAudioContainer.ID3_V23, MetadataAudioContainer.ID3_V24),
        tagRevisions = setOf(3, 4),
        supportedFields = setOf(MetadataField.TITLE),
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
