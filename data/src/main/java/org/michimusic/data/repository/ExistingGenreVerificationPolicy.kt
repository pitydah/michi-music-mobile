package org.michimusic.data.repository

import java.security.MessageDigest
import org.michimusic.core.models.Track

/** A single verification page is bounded; no persistent scan or automatic correction. */
data class ExistingGenreDifference(
    val trackId: String,
    val title: String,
    val indexedGenre: String,
    val embeddedGenre: String,
)

data class ExistingGenreVerificationReport(
    val examined: Int,
    val candidates: Int,
    val nextOffset: Int?,
    /** Stable token for the exact owner, ordering, and membership of this page series. */
    val snapshotToken: String,
    val matching: Int,
    val differing: Int,
    val withoutEmbeddedGenre: Int,
    val unreadable: Int,
    val unverifiable: Int,
    /** Only the first 24 differences from this page; no filesystem URI is exposed. */
    val differences: List<ExistingGenreDifference>,
    /** A subset of unreadable: explicit permission denials, not absent tags. */
    val permissionDenied: Int = 0,
)

internal enum class ExistingGenreComparison { MATCH, DIFFERENT, NO_EMBEDDED_GENRE, READ_FAILED }

/** Comparison deliberately does NOT split slash or semicolon; a literal genre remains intact. */
internal object ExistingGenreVerificationPolicy {
    const val PAGE_SIZE = 128
    const val MAX_VISIBLE_DIFFERENCES = 24

    /** Fail closed on malformed or stale continuation offsets. No clamping to an empty success. */
    fun continuationStart(
        offset: Int, candidates: Int, actualToken: String, expectedToken: String?,
    ): Int? {
        if (offset < 0 || candidates < 0 || offset > candidates ||
            (candidates > 0 && offset == candidates)) return null
        if (offset > 0 && (expectedToken == null || expectedToken != actualToken)) return null
        return offset
    }

    /** Detect changed membership or order across manual pages; never hashes audio bytes. */
    fun snapshotToken(owner: String, tracks: List<Track>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun feed(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val size = bytes.size
            digest.update(byteArrayOf(
                (size ushr 24).toByte(), (size ushr 16).toByte(),
                (size ushr 8).toByte(), size.toByte(),
            ))
            digest.update(bytes)
        }
        feed(owner)
        feed(tracks.size.toString())
        tracks.forEach { track ->
            feed(track.sourceId)
            feed(track.id)
            feed(track.filepath)
            feed(track.size.toString())
            feed(track.title)
            feed(track.artist)
            feed(track.album)
            feed(track.genre)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun compare(indexed: String, fields: EmbeddedTagFields?): ExistingGenreComparison {
        if (fields == null) return ExistingGenreComparison.READ_FAILED
        val embedded = GenreMetadataMerge.choose(fields.genre, null, null)
        if (embedded.isBlank()) return ExistingGenreComparison.NO_EMBEDDED_GENRE
        val current = GenreMetadataMerge.choose(null, indexed, null)
        return if (embedded.trim().equals(current.trim(), ignoreCase = true))
            ExistingGenreComparison.MATCH else ExistingGenreComparison.DIFFERENT
    }
}
