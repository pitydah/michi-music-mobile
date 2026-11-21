package org.michimusic.core.metadata

/**
 * ME-11I: prepares a structured genre observation for the EXISTING Room sidecar.
 * The scalar cached genre is only a first-value index hint; the complete ordered,
 * duplicate-preserving list is stored in embedded_tag_evidence.genreValues.
 * This policy performs NO I/O and can never authorize physical source writes.
 */
object MetadataStructuredGenreRefreshPolicy {
    enum class Reason {
        NOT_A_GENRE_ONLY_REFRESH, INVALID_TARGET, SOURCE_NOT_VERIFIED,
        GENRES_NOT_VERIFIED, INVALID_GENRE_VALUE, INDEX_FIELDS_NOT_VERIFIED,
    }
    sealed interface Result {
        data class Blocked(val reason: Reason) : Result
        data class Prepared(
            val primaryGenre: String,
            val genreValues: String,
            val values: List<String>,
            /** A prepared index projection is NOT a certified commit receipt. */
            val mayPublishNow: Boolean = false,
        ) : Result
    }

    // Evidence fields mirrored by EmbeddedTagEvidence. Absence must be VERIFIED,
    // not silently guessed from a parser that did not understand the tag.
    val requiredEvidenceFields: Set<MetadataField> = setOf(
        MetadataField.TITLE, MetadataField.ARTIST, MetadataField.ALBUM,
        MetadataField.ALBUM_ARTIST, MetadataField.GENRE, MetadataField.COMPOSER,
        MetadataField.TRACK_NUMBER, MetadataField.TRACK_TOTAL,
        MetadataField.DISC_NUMBER, MetadataField.DISC_TOTAL,
        MetadataField.RELEASE_DATE,
    )

    fun prepare(delta: MetadataSourceIndexDelta.Delta): Result {
        val verdict = MetadataScalarIndexRefreshPolicy.evaluate(delta)
        if (verdict !is MetadataScalarIndexRefreshPolicy.Verdict.SelectiveRefreshRequired ||
            verdict.reason != MetadataScalarIndexRefreshPolicy.Reason.AMBIGUOUS_MULTIVALUE ||
            verdict.fields != setOf(MetadataField.GENRE))
            return Result.Blocked(Reason.NOT_A_GENRE_ONLY_REFRESH)
        if (delta.target.trackId.isBlank() || !delta.target.uri.startsWith("content://") ||
            delta.candidateBytes !in 12..(1024L * 1024L * 1024L) ||
            !delta.sourceSha256.matches(Regex("[a-f0-9]{64}")))
            return Result.Blocked(Reason.INVALID_TARGET)
        if (delta.untouchedUnknown.intersect(requiredEvidenceFields).isNotEmpty() ||
            !delta.observedValues.keys.containsAll(requiredEvidenceFields))
            return Result.Blocked(Reason.INDEX_FIELDS_NOT_VERIFIED)
        val genres = delta.observedValues[MetadataField.GENRE]
            ?: return Result.Blocked(Reason.GENRES_NOT_VERIFIED)
        if (genres.size !in 2..32) return Result.Blocked(Reason.GENRES_NOT_VERIFIED)
        if (genres.any { it.isBlank() || it.length > MetadataField.GENRE.maxChars ||
                '\u0000' in it || it.any(Char::isSurrogate) })
            return Result.Blocked(Reason.INVALID_GENRE_VALUE)
        return Result.Prepared(genres.first(), genres.joinToString("\u0000"), genres.toList())
    }
}
