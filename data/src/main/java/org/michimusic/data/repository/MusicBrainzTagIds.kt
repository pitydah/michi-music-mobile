package org.michimusic.data.repository

import java.util.Locale

/**
 * Reads identity tokens ONLY from structured MusicBrainz fields already extracted
 * from an authorized local metadata read. These are unverified observations:
 * they never assign a local artist, split a display credit, or call the network.
 */
internal data class MusicBrainzTagIds(
    val artistIds: List<String> = emptyList(),
    val albumArtistIds: List<String> = emptyList(),
    val recordingIds: List<String> = emptyList(),
    val releaseIds: List<String> = emptyList(),
)

internal object MusicBrainzTagIdPolicy {
    private val uuid = Regex("(?i)^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$")
    private val boundaries = Regex("[\\s;,/|\\u0000]+")

    internal fun canonicalKey(value: String): String = value.uppercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun candidates(values: List<String>): List<String> = values.asSequence()
        .take(32) // a single corrupt tag cannot create unbounded work
        .flatMap { it.split(boundaries).asSequence().take(16) }
        .filter { uuid.matches(it) }
        .map { it.lowercase(Locale.ROOT) }
        .distinct()
        .take(8)
        .toList()

    fun fromTags(tags: Map<String, List<String>>): MusicBrainzTagIds {
        val art = ArrayList<String>()
        val album = ArrayList<String>()
        val recordings = ArrayList<String>()
        val releases = ArrayList<String>()
        // Preserve role: Artist Id and Album Artist Id are not interchangeable.
        for ((rawKey, rawValues) in tags) {
            when (canonicalKey(rawKey)) {
                "MUSICBRAINZARTISTID" -> art.addAll(rawValues.take(32))
                "MUSICBRAINZALBUMARTISTID" -> album.addAll(rawValues.take(32))
                // Picard uses TRACKID for recording MBID and ALBUMID for release MBID.
                "MUSICBRAINZTRACKID", "MUSICBRAINZRECORDINGID" -> recordings.addAll(rawValues.take(32))
                "MUSICBRAINZALBUMID", "MUSICBRAINZRELEASEID" -> releases.addAll(rawValues.take(32))
            }
        }
        return MusicBrainzTagIds(candidates(art), candidates(album), candidates(recordings), candidates(releases))
    }
}
