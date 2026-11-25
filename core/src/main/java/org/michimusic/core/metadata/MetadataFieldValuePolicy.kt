package org.michimusic.core.metadata

/**
 * ME-10X: high-confidence syntax checks for newly proposed typed tag values.
 * This never rewrites pre-existing metadata and never guesses a canonical value.
 * Formats with legitimate free-text conventions remain unrestricted here;
 * physical format writer validation is an independent, stricter boundary.
 */
object MetadataFieldValuePolicy {
    enum class Failure { NUMBER_OUT_OF_RANGE, INVALID_BPM, INVALID_ISRC,
        INVALID_MUSICBRAINZ_ID, INVALID_DISCOGS_ID }
    private val number=Regex("[1-9][0-9]{0,3}")
    private val bpm=Regex("[0-9]{1,4}(\\.[0-9]{1,3})?")
    private val isrc=Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
    private val discogs=Regex("[1-9][0-9]{0,11}")
    fun inspect(field: MetadataField, values: List<String>): Failure? {
        if (field in MetadataMusicBrainzTagMap.id3Fields)
            return if (MetadataMusicBrainzTagMap.valid(field,values)) null
                else Failure.INVALID_MUSICBRAINZ_ID
        return when (field) {
            MetadataField.TRACK_NUMBER, MetadataField.TRACK_TOTAL,
            MetadataField.DISC_NUMBER, MetadataField.DISC_TOTAL,
            MetadataField.MOVEMENT_NUMBER, MetadataField.MOVEMENT_TOTAL ->
                if (values.all { number.matches(it) && (it.toIntOrNull() ?: 0) in 1..9999 }) null
                else Failure.NUMBER_OUT_OF_RANGE
            MetadataField.BPM ->
                if (values.all { bpm.matches(it) && (it.toDoubleOrNull() ?: 0.0) in 0.1..9999.0 }) null
                else Failure.INVALID_BPM
            MetadataField.ISRC -> if (values.all(isrc::matches)) null else Failure.INVALID_ISRC
            MetadataField.DISCOGS_RELEASE_ID ->
                if (values.all(discogs::matches)) null else Failure.INVALID_DISCOGS_ID
            else -> null
        }
    }
}
