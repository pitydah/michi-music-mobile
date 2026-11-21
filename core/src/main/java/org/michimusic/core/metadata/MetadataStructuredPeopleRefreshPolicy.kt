package org.michimusic.core.metadata

/**
 * ME-11K: lossless, exact ordered artist and album-artist lists for the local Room
 * sidecar. Scalar cache fields are a compatibility hint, not the sole truth.
 * No I/O, no storage permission, and no claim that a physical write succeeded.
 */
object MetadataStructuredPeopleRefreshPolicy {
    private val people = setOf(MetadataField.ARTIST, MetadataField.ALBUM_ARTIST)
    private val required = MetadataStructuredGenreRefreshPolicy.requiredEvidenceFields
    enum class Reason {
        WRONG_SELECTIVE_FIELD, INVALID_SOURCE, PARTIAL_EVIDENCE,
        INVALID_VALUES, OTHER_MULTIVALUE_FIELD,
    }
    sealed interface Result {
        data class Blocked(val reason: Reason) : Result
        data class Prepared(
            val artist: String,
            val albumArtist: String,
            val artists: List<String>,
            val albumArtists: List<String>,
            val artistsPacked: String,
            val albumArtistsPacked: String,
            /** NEVER conveys permission to publish or write any file. */
            val sourceWriteAuthorized: Boolean = false,
        ) : Result
    }

    fun inspect(delta: MetadataSourceIndexDelta.Delta): Result {
        if (delta.target.trackId.isBlank() || !delta.target.uri.startsWith("content://") ||
            !delta.sourceSha256.matches(Regex("[0-9a-f]{64}")) ||
            delta.candidateBytes !in 12..(1024L * 1024L * 1024L))
            return Result.Blocked(Reason.INVALID_SOURCE)
        if (delta.untouchedUnknown.intersect(required).isNotEmpty() ||
            !delta.observedValues.keys.containsAll(required))
            return Result.Blocked(Reason.PARTIAL_EVIDENCE)
        val issue = MetadataScalarIndexRefreshPolicy.evaluate(delta)
        if (issue !is MetadataScalarIndexRefreshPolicy.Verdict.SelectiveRefreshRequired ||
            issue.reason != MetadataScalarIndexRefreshPolicy.Reason.AMBIGUOUS_MULTIVALUE ||
            issue.fields.isEmpty() || !people.containsAll(issue.fields))
            return Result.Blocked(Reason.WRONG_SELECTIVE_FIELD)
        val artists = delta.observedValues[MetadataField.ARTIST] ?: return Result.Blocked(Reason.PARTIAL_EVIDENCE)
        val albumArtists = delta.observedValues[MetadataField.ALBUM_ARTIST] ?: return Result.Blocked(Reason.PARTIAL_EVIDENCE)
        if (artists.size > 32 || albumArtists.size > 32 ||
            (artists.size < 2 && albumArtists.size < 2) ||
            !(artists + albumArtists).all { valid(it) })
            return Result.Blocked(Reason.INVALID_VALUES)
        val otherList = required.filter { it !in people && delta.observedValues[it].orEmpty().size > 1 }
        if (otherList.isNotEmpty()) return Result.Blocked(Reason.OTHER_MULTIVALUE_FIELD)
        return Result.Prepared(artists.firstOrNull().orEmpty(), albumArtists.firstOrNull().orEmpty(),
            artists.toList(), albumArtists.toList(), artists.joinToString("\u0000"),
            albumArtists.joinToString("\u0000"))
    }

    private fun valid(value: String): Boolean = value.isNotBlank() && value.length <= 2048 &&
        '\u0000' !in value && !value.any(Char::isSurrogate)
}
