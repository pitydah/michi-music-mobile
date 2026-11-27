package org.michimusic.data.metadata

import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSourceIndexDelta
import org.michimusic.data.cache.EmbeddedTagEvidence

/**
 * ME-19: exact Room sidecar projection for fields the scalar track row cannot
 * represent. Values come only from the independently cold-parsed committed
 * candidate; a missing observation is a refusal, never an invented empty value.
 */
internal object MetadataEmbeddedEvidence {
    /** Changed fields whose only durable Room representation is the sidecar. */
    val nonProjectedFields: Set<MetadataField> = setOf(
        MetadataField.COMPOSER,
        MetadataField.TRACK_TOTAL,
        MetadataField.DISC_NUMBER,
        MetadataField.DISC_TOTAL,
    )

    fun fromDelta(
        delta: MetadataSourceIndexDelta.Delta,
        modifiedAtProviderUnits: Long,
    ): EmbeddedTagEvidence? {
        if (modifiedAtProviderUnits <= 0L) return null
        fun text(field: MetadataField): String? {
            val values = delta.observedValues[field] ?: return null
            return if (values.size <= 1) values.firstOrNull().orEmpty() else null
        }
        fun number(field: MetadataField): Int? {
            val values = delta.observedValues[field] ?: return null
            if (values.isEmpty()) return 0
            if (values.size != 1 || !values[0].matches(Regex("[0-9]{1,4}")))
                return null
            return values[0].toIntOrNull()?.takeIf { it in 1..9999 }
        }
        val genres = delta.observedValues[MetadataField.GENRE] ?: return null
        return EmbeddedTagEvidence(
            sourceId = delta.target.sourceId,
            trackId = delta.target.trackId,
            sizeBytes = delta.candidateBytes,
            modifiedAt = modifiedAtProviderUnits,
            parserVersion = EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
            genre = genres.firstOrNull().orEmpty(),
            genreValues = genres.joinToString("\u0000"),
            title = text(MetadataField.TITLE) ?: return null,
            artist = text(MetadataField.ARTIST) ?: return null,
            album = text(MetadataField.ALBUM) ?: return null,
            albumArtist = text(MetadataField.ALBUM_ARTIST) ?: return null,
            composer = text(MetadataField.COMPOSER) ?: return null,
            trackNumber = number(MetadataField.TRACK_NUMBER) ?: return null,
            trackTotal = number(MetadataField.TRACK_TOTAL) ?: return null,
            discNumber = number(MetadataField.DISC_NUMBER) ?: return null,
            discTotal = number(MetadataField.DISC_TOTAL) ?: return null,
            date = text(MetadataField.RELEASE_DATE) ?: return null,
        )
    }
}
