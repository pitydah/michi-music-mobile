package org.michimusic.data.repository

import org.michimusic.data.cache.EmbeddedTagEvidence

/** Pure conversion. This policy does not normalize, infer, or collapse genre names. */
internal object TagEvidencePolicy {
    const val MEDIASTORE_OWNER = ""

    /** Do not scan a file whose provider metadata is already sufficient. */
    fun needsProbe(genre: String?, title: String?, artist: String?, album: String?): Boolean =
        GenreMetadataMerge.choose(null, genre, null).isBlank() ||
            MetadataFieldMerge.choose(null, title).isBlank() ||
            MetadataFieldMerge.choose(null, artist).isBlank() ||
            MetadataFieldMerge.choose(null, album).isBlank()

    /** A four-digit year is accepted only from an unambiguous date tag. */
    fun exactYear(date: String): Int? {
        val match = Regex("^([0-9]{4})(?:$|-[0-9]{2}(?:-[0-9]{2})?$)").matchEntire(date)
            ?: return null
        return match.groupValues[1].toIntOrNull()?.takeIf { it in 1000..9999 }
    }

    fun record(
        sourceId: String,
        trackId: String,
        sizeBytes: Long,
        modifiedAt: Long,
        fields: EmbeddedTagFields?,
    ): EmbeddedTagEvidence {
        val genres = fields?.genres.orEmpty().map(String::trim)
            .filter { it.isNotEmpty() && it.length <= 2048 && '\uFFFD' !in it }
            .distinct().take(32)
        return EmbeddedTagEvidence(
            sourceId = sourceId,
            trackId = trackId,
            sizeBytes = sizeBytes.coerceAtLeast(0),
            modifiedAt = modifiedAt.coerceAtLeast(0),
            parserVersion = EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
            // Track.genre is the compatibility field for the existing Genre UI;
            // full, unambiguous values remain in genreValues with NUL separators.
            genre = genres.joinToString("; "),
            genreValues = genres.joinToString("\u0000"),
            title = fields?.title.orEmpty(),
            artist = fields?.artist.orEmpty(),
            album = fields?.album.orEmpty(),
            albumArtist = fields?.albumArtist.orEmpty(),
            composer = fields?.composer.orEmpty(),
            trackNumber = fields?.trackNumber ?: 0,
            trackTotal = fields?.trackTotal ?: 0,
            discNumber = fields?.discNumber ?: 0,
            discTotal = fields?.discTotal ?: 0,
            date = fields?.date.orEmpty(),
        )
    }
}
