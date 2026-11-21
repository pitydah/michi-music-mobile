package org.michimusic.core.metadata

/**
 * ME-01 offline, on-demand review of ALREADY selected metadata snapshots.
 * Findings are observations/suggestions, never authoritative genre classification.
 */
object MetadataLibraryDiagnostics {
    enum class Kind {
        EVIDENCE_UNVERIFIED,
        TITLE_NOT_PRESENT,
        ARTIST_NOT_PRESENT,
        ALBUM_NOT_PRESENT,
        GENRE_NOT_PRESENT,
        ALBUM_ARTIST_NOT_PRESENT,
        LEADING_OR_TRAILING_SPACES,
        DUPLICATE_TRACK_NUMBER_IN_SELECTION,
    }

    data class Finding(
        val target: MetadataTarget,
        val kind: Kind,
        val field: MetadataField? = null,
    )

    /** Does not open paths, read media, schedule work, infer genres, or fix tags. */
    fun inspect(snapshots: List<MetadataSnapshot>): List<Finding> {
        require(snapshots.size <= 128) { "Selection exceeds bounded diagnostics budget" }
        val output = mutableListOf<Finding>()
        for (snapshot in snapshots) {
            val target = snapshot.target
            if (!snapshot.tagsVerified) {
                output += Finding(target, Kind.EVIDENCE_UNVERIFIED)
                continue
            }
            val required = listOf(
                MetadataField.TITLE to Kind.TITLE_NOT_PRESENT,
                MetadataField.ARTIST to Kind.ARTIST_NOT_PRESENT,
                MetadataField.ALBUM to Kind.ALBUM_NOT_PRESENT,
                MetadataField.GENRE to Kind.GENRE_NOT_PRESENT,
                MetadataField.ALBUM_ARTIST to Kind.ALBUM_ARTIST_NOT_PRESENT,
            )
            for ((field, kind) in required)
                if (field in snapshot.verifiedFields && snapshot.known[field].orEmpty().isEmpty())
                    output += Finding(target, kind, field)
            snapshot.known.forEach { (field, values) ->
                if (field in snapshot.verifiedFields && values.any { it != it.trim() })
                    output += Finding(target, Kind.LEADING_OR_TRAILING_SPACES, field)
            }
        }
        // Duplicate numbers are only *suggestions*: a selection can contain editions/versions.
        // If album title or artist is absent, never infer grouping from a filename.
        snapshots.filter { it.tagsVerified &&
            it.verifiedFields.containsAll(setOf(MetadataField.ALBUM, MetadataField.ALBUM_ARTIST,
                MetadataField.TRACK_NUMBER, MetadataField.DISC_NUMBER)) }.groupBy { snapshot ->
            val known = snapshot.known
            val album = known[MetadataField.ALBUM]?.singleOrNull()
            val albumArtist = known[MetadataField.ALBUM_ARTIST]?.singleOrNull()
            val disc = known[MetadataField.DISC_NUMBER]?.singleOrNull() ?: "1"
            val track = known[MetadataField.TRACK_NUMBER]?.singleOrNull()
            if (album != null && albumArtist != null && track != null)
                listOf(snapshot.target.sourceId, album, albumArtist, disc, track)
            else null
        }.forEach { (key, matching) ->
            if (key != null && matching.size > 1) for (snapshot in matching)
                output += Finding(snapshot.target, Kind.DUPLICATE_TRACK_NUMBER_IN_SELECTION,
                    MetadataField.TRACK_NUMBER)
        }
        return output
    }
}
