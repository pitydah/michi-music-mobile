package org.michimusic.core.metadata

/** ME-04 READ. These observations describe physical embedded bytes, not Room display text. */
enum class MetadataPhysicalContainer { ID3_V23, ID3_V24, FLAC, OGG_VORBIS, OGG_OPUS, MP4_M4A, WAVPACK, APE, WAV, AIFF, UNKNOWN }

enum class MetadataObservationIssue {
    UNSUPPORTED_CONTAINER, UNSUPPORTED_VERSION, UNSUPPORTED_FLAGS,
    MALFORMED_STRUCTURE, TRUNCATED, RESOURCE_LIMIT, BAD_TEXT_ENCODING,
    INVALID_FIELD, DUPLICATE_FIELD, AMBIGUOUS_ALIAS, LEGACY_READER_DISAGREES,
    PROVIDER_UNSEEKABLE, FIELD_NOT_SUPPORTED,
}

sealed interface MetadataFieldObservation {
    /** Values are distinct entries; an internal semicolon is never a delimiter. */
    data class Present(val values: List<String>) : MetadataFieldObservation
    /** Physical absence is proven only when that field's format-specific coverage is complete. */
    data object VerifiedAbsent : MetadataFieldObservation
    data class Unverifiable(val issue: MetadataObservationIssue) : MetadataFieldObservation
    data class Conflicted(val issue: MetadataObservationIssue) : MetadataFieldObservation
}

data class MetadataFieldReadReport(
    val container: MetadataPhysicalContainer,
    val parserVersion: Int,
    val observations: Map<MetadataField, MetadataFieldObservation>,
) {
    /** Never elevate a field lacking a complete observation to an editable field. */
    val verifiedFields: Set<MetadataField>
        get() = observations.filterValues {
            it is MetadataFieldObservation.Present || it == MetadataFieldObservation.VerifiedAbsent
        }.keys

    fun observation(field: MetadataField): MetadataFieldObservation =
        observations[field] ?: MetadataFieldObservation.Unverifiable(
            MetadataObservationIssue.UNSUPPORTED_CONTAINER)
}

/** A deliberately small P0 contract; other fields remain explicitly UNVERIFIABLE. */
object MetadataReadFieldCatalog {
    val primary = linkedSetOf(
        MetadataField.TITLE, MetadataField.ARTIST, MetadataField.ALBUM,
        MetadataField.ALBUM_ARTIST, MetadataField.GENRE, MetadataField.COMPOSER,
        MetadataField.TRACK_NUMBER, MetadataField.TRACK_TOTAL,
        MetadataField.DISC_NUMBER, MetadataField.DISC_TOTAL, MetadataField.RELEASE_DATE,
        MetadataField.SUBTITLE, MetadataField.ORIGINAL_RELEASE_DATE,
        MetadataField.LABEL, MetadataField.CATALOG_NUMBER, MetadataField.ISRC,
        MetadataField.BPM, MetadataField.MUSICAL_KEY,
        MetadataField.ALBUM_SUBTITLE,
        MetadataField.ARRANGER,
        MetadataField.COMMENT,
        MetadataField.COMPILATION,
        MetadataField.CONDUCTOR,
        MetadataField.COPYRIGHT,
        MetadataField.EDITION,
        MetadataField.ENSEMBLE,
        MetadataField.EXECUTIVE_PRODUCER,
        MetadataField.GROUPING,
        MetadataField.INSTRUMENT,
        MetadataField.LANGUAGE,
        MetadataField.LYRICIST,
        MetadataField.MASTERING_ENGINEER,
        MetadataField.MEDIA_FORMAT,
        MetadataField.MOOD,
        MetadataField.MOVEMENT,
        MetadataField.MOVEMENT_NUMBER,
        MetadataField.MOVEMENT_TOTAL,
        MetadataField.OPUS_NUMBER,
        MetadataField.ORCHESTRA,
        MetadataField.ORIGINAL_ARTIST,
        MetadataField.PERFORMER,
        MetadataField.PRODUCER,
        MetadataField.PUBLISHER,
        MetadataField.RECORDING_DATE,
        MetadataField.RELEASE_STATUS,
        MetadataField.RELEASE_TYPE,
        MetadataField.REMIXER,
        MetadataField.SCRIPT,
        MetadataField.SOLOIST,
        MetadataField.WORK,
        MetadataField.WORK_NUMBER,
        MetadataField.LYRICS,
        MetadataField.MUSICBRAINZ_RECORDING_ID,
        MetadataField.MUSICBRAINZ_RELEASE_ID,
        MetadataField.MUSICBRAINZ_RELEASE_TRACK_ID,
        MetadataField.MUSICBRAINZ_RELEASE_GROUP_ID,
        MetadataField.MUSICBRAINZ_WORK_ID,
        MetadataField.MUSICBRAINZ_ARTIST_ID,
        MetadataField.MUSICBRAINZ_LABEL_ID,
    )
    const val PARSER_VERSION = 3
    fun unavailable(issue: MetadataObservationIssue,
                    container: MetadataPhysicalContainer = MetadataPhysicalContainer.UNKNOWN): MetadataFieldReadReport =
        MetadataFieldReadReport(container, PARSER_VERSION,
            primary.associateWith { MetadataFieldObservation.Unverifiable(issue) })
}
