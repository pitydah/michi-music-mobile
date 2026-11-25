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
    const val ID = "MS_LOCAL_MP3_ID3_V23V24_TEXT_V1"
    const val EVIDENCE_ID = "ME13_MEDIASTORE_MP3_ID3_TEXT_V1"

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
    )
}

/** FLAC / Vorbis Comment scalar profile on the local MediaStore provider. */
object MediaStoreFlacTextProfile {
    const val ID = "MS_LOCAL_FLAC_VORBIS_TEXT_V1"
    const val EVIDENCE_ID = "ME15_MEDIASTORE_FLAC_VORBIS_TEXT_V1"
    val profile = CertifiedMetadataWriteProfile(
        id = ID,
        provider = MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO,
        containers = setOf(MetadataAudioContainer.FLAC),
        tagRevisions = setOf(1),
        supportedFields = setOf(
            MetadataField.TITLE, MetadataField.ARTIST, MetadataField.ALBUM,
            MetadataField.ALBUM_ARTIST, MetadataField.GENRE, MetadataField.COMPOSER,
            MetadataField.RELEASE_DATE, MetadataField.TRACK_NUMBER,
            MetadataField.TRACK_TOTAL, MetadataField.DISC_NUMBER,
            MetadataField.DISC_TOTAL, MetadataField.LABEL,
            MetadataField.CATALOG_NUMBER, MetadataField.ISRC, MetadataField.BPM,
            MetadataField.MUSICAL_KEY, MetadataField.COMMENT,
            MetadataField.COPYRIGHT, MetadataField.LYRICIST,
            MetadataField.PRODUCER, MetadataField.PERFORMER,
            MetadataField.CONDUCTOR, MetadataField.LYRICS,
        ),
        permitsArtwork = false,
        permitsMultiValue = emptySet(),
        capabilityEvidenceId = EVIDENCE_ID,
    )
}

object MetadataWriteProfileRegistry {
    fun byId(id: String): CertifiedMetadataWriteProfile? = when (id) {
        MediaStoreMp3TextProfile.ID -> MediaStoreMp3TextProfile.profile
        MediaStoreFlacTextProfile.ID -> MediaStoreFlacTextProfile.profile
        else -> null
    }
    fun forContainer(container: MetadataAudioContainer): CertifiedMetadataWriteProfile? =
        when (container) {
            MetadataAudioContainer.ID3_V23, MetadataAudioContainer.ID3_V24 ->
                MediaStoreMp3TextProfile.profile
            MetadataAudioContainer.FLAC -> MediaStoreFlacTextProfile.profile
            else -> null
        }
}
