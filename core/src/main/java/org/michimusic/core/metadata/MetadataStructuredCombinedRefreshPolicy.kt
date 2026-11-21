package org.michimusic.core.metadata

/**
 * ME-11L: an actual postcheck may reveal BOTH multi-genre and multi-person
 * fields. They require ONE atomic index publication, not two successive CAS
 * writes (the first invalidates the old source proof). This is a pure proposal.
 * In particular, it does NOT certify source writes, provider recovery, or Room.
 */
object MetadataStructuredCombinedRefreshPolicy {
    enum class Reason {
        WRONG_FIELDS, INVALID_TARGET, PARTIAL_EVIDENCE, INVALID_GENRES,
        INVALID_PEOPLE, OTHER_MULTIVALUE,
    }
    sealed interface Result {
        data class Blocked(val reason: Reason) : Result
        data class Prepared(
            val genre: String,
            val genres: List<String>,
            val genreValues: String,
            val artist: String,
            val artists: List<String>,
            val artistValues: String,
            val albumArtist: String,
            val albumArtists: List<String>,
            val albumArtistValues: String,
            /** A policy result can NEVER authorize source or cache publication. */
            val mayPublishNow: Boolean = false,
        ) : Result
    }
    private val people = setOf(MetadataField.ARTIST, MetadataField.ALBUM_ARTIST)
    private val supported = people + MetadataField.GENRE
    private val required = MetadataStructuredGenreRefreshPolicy.requiredEvidenceFields
    private val shaPattern = Regex("[0-9a-f]{64}")

    fun inspect(delta: MetadataSourceIndexDelta.Delta): Result {
        if (delta.target.trackId.isBlank() || !delta.target.uri.startsWith("content://") ||
            !shaPattern.matches(delta.sourceSha256) ||
            delta.candidateBytes !in 12..(1024L * 1024L * 1024L))
            return Result.Blocked(Reason.INVALID_TARGET)
        if (!delta.observedValues.keys.containsAll(required) ||
            delta.untouchedUnknown.intersect(required).isNotEmpty())
            return Result.Blocked(Reason.PARTIAL_EVIDENCE)
        val issue = MetadataScalarIndexRefreshPolicy.evaluate(delta)
            as? MetadataScalarIndexRefreshPolicy.Verdict.SelectiveRefreshRequired
            ?: return Result.Blocked(Reason.WRONG_FIELDS)
        if (issue.reason != MetadataScalarIndexRefreshPolicy.Reason.AMBIGUOUS_MULTIVALUE ||
            MetadataField.GENRE !in issue.fields ||
            issue.fields.intersect(people).isEmpty() ||
            !supported.containsAll(issue.fields))
            return Result.Blocked(Reason.WRONG_FIELDS)
        val genres = delta.observedValues[MetadataField.GENRE].orEmpty()
        if (genres.size !in 2..32 || genres.any { !valid(it) })
            return Result.Blocked(Reason.INVALID_GENRES)
        val artists = delta.observedValues[MetadataField.ARTIST].orEmpty()
        val albumArtists = delta.observedValues[MetadataField.ALBUM_ARTIST].orEmpty()
        if (artists.size > 32 || albumArtists.size > 32 ||
            (artists.size < 2 && albumArtists.size < 2) ||
            !(artists + albumArtists).all { valid(it) })
            return Result.Blocked(Reason.INVALID_PEOPLE)
        if (required.any { it !in supported && delta.observedValues[it].orEmpty().size > 1 })
            return Result.Blocked(Reason.OTHER_MULTIVALUE)
        return Result.Prepared(
            genre = genres.first(), genres = genres.toList(), genreValues = genres.joinToString("\u0000"),
            artist = artists.firstOrNull().orEmpty(), artists = artists.toList(),
            artistValues = artists.joinToString("\u0000"),
            albumArtist = albumArtists.firstOrNull().orEmpty(), albumArtists = albumArtists.toList(),
            albumArtistValues = albumArtists.joinToString("\u0000"),
        )
    }
    private fun valid(value: String): Boolean = value.isNotBlank() && value.length <= 2048 &&
        '\u0000' !in value && value.none(Char::isSurrogate)
}
