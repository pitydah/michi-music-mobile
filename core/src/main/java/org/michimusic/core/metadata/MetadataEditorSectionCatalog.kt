package org.michimusic.core.metadata

/** Stable order for the six editorial groups. A field appears in exactly one group. */
enum class MetadataEditorSection(val fields: List<MetadataField>) {
    BASICS(listOf(
        MetadataField.TITLE, MetadataField.SUBTITLE, MetadataField.ARTIST,
        MetadataField.ALBUM, MetadataField.ALBUM_SUBTITLE, MetadataField.ALBUM_ARTIST, MetadataField.GENRE,
        MetadataField.TRACK_NUMBER, MetadataField.TRACK_TOTAL,
        MetadataField.DISC_NUMBER, MetadataField.DISC_TOTAL,
    )),
    CREDITS(listOf(
        MetadataField.COMPOSER, MetadataField.LYRICIST, MetadataField.ARRANGER,
        MetadataField.PRODUCER, MetadataField.CONDUCTOR, MetadataField.PERFORMER,
        MetadataField.ENGINEER, MetadataField.MASTERING_ENGINEER,
        MetadataField.EXECUTIVE_PRODUCER, MetadataField.MIXER, MetadataField.REMIXER,
        MetadataField.ORCHESTRA, MetadataField.ENSEMBLE, MetadataField.SOLOIST,
        MetadataField.INSTRUMENT, MetadataField.ORIGINAL_ARTIST,
        MetadataField.WORK, MetadataField.WORK_NUMBER, MetadataField.OPUS_NUMBER,
        MetadataField.MOVEMENT, MetadataField.MOVEMENT_NUMBER, MetadataField.MOVEMENT_TOTAL,
    )),
    DISCOGRAPHY(listOf(
        MetadataField.RELEASE_DATE, MetadataField.RECORDING_DATE,
        MetadataField.ORIGINAL_RELEASE_DATE, MetadataField.EDITION,
        MetadataField.RELEASE_TYPE, MetadataField.RELEASE_STATUS,
        MetadataField.MEDIA_FORMAT, MetadataField.COMPILATION,
        MetadataField.LABEL, MetadataField.CATALOG_NUMBER,
        MetadataField.ISRC, MetadataField.BARCODE,
        MetadataField.RELEASE_COUNTRY, MetadataField.PUBLISHER,
    )),
    ART_LYRICS(listOf(
        MetadataField.COMMENT, MetadataField.LYRICS, MetadataField.SYNCED_LYRICS,
    )),
    IDENTIFIERS(listOf(
        MetadataField.MUSICBRAINZ_RECORDING_ID, MetadataField.MUSICBRAINZ_RELEASE_TRACK_ID,
        MetadataField.MUSICBRAINZ_RELEASE_ID, MetadataField.MUSICBRAINZ_RELEASE_GROUP_ID,
        MetadataField.MUSICBRAINZ_ARTIST_ID, MetadataField.MUSICBRAINZ_WORK_ID,
        MetadataField.DISCOGS_RELEASE_ID, MetadataField.MUSICBRAINZ_LABEL_ID,
    )),
    ADVANCED(listOf(
        MetadataField.GROUPING, MetadataField.MOOD, MetadataField.LANGUAGE,
        MetadataField.COPYRIGHT, MetadataField.WEBSITE, MetadataField.BPM,
        MetadataField.MUSICAL_KEY, MetadataField.SCRIPT,
    )),
}
