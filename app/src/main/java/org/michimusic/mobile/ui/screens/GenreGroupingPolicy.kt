package org.michimusic.mobile.ui.screens

import org.michimusic.core.models.Track
import java.util.Locale
import java.text.Normalizer

/** A genre is an index into the authoritative scoped tracks, never a new audio owner. */
data class LibraryGenre(
    val id: String,
    val title: String,
    val tracks: List<Track>,
    val albumCount: Int,
) {
}

private const val UNKNOWN_GENRE_ID = "__ui7_unknown_genre__"
private const val UNKNOWN_GENRE_TITLE = "Sin clasificar"

private data class GenreToken(val key: String, val title: String)
private fun genreNames(raw: String): List<GenreToken> {
    val unique = linkedMapOf<String, GenreToken>()
    // Refuse pathological metadata instead of allocating thousands of UI groups.
    if (raw.length > 8_192) return emptyList()
    raw.split(';', '\u0000', '\u001f').forEach { rawName ->
        val label = genreCleanLabel(rawName)
        val key = genreGroupingKey(label)
        if (!genreTagIsUnknownKey(key)) {
            unique.putIfAbsent(key, GenreToken(key, genreCanonicalDisplayName(key, label)))
            if (unique.size > 32) return emptyList() // Never publish partial categories.
        }
    }
    // Slash is not a delimiter: styles such as "Rock/Pop" are valid single labels.
    return unique.values.toList()
}

/**
 * Pure scoped grouping, never scans the filesystem or invokes providers. Optional
 * multi-value genre tags separated by semicolon or NUL are indexed individually.
 * Untagged tracks remain visible under an explicit, non-inferred bucket.
 */
fun groupLibraryGenres(tracks: List<Track>): List<LibraryGenre> {
    data class Bucket(val title: String, val tracks: MutableList<Track>)
    val buckets = linkedMapOf<String, Bucket>()
    // Avoid duplicate rows if a scope presents a track through more than one album.
    tracks.distinctBy { Triple(it.sourceId, it.id, it.filepath) }.forEach { track ->
        val names = genreNames(track.genre).ifEmpty { listOf(GenreToken(UNKNOWN_GENRE_ID, UNKNOWN_GENRE_TITLE)) }
        names.forEach { name ->
            val id = name.key
            val bucket = buckets.getOrPut(id) { Bucket(name.title, mutableListOf()) }
            val preferred = genrePreferredDisplayTitle(bucket.title, name.title)
            if (preferred != bucket.title) buckets[id] = Bucket(preferred, bucket.tracks)
            bucket.tracks.add(track)
        }
    }
    return buckets.map { (id, bucket) ->
        LibraryGenre(
            id = id,
            title = bucket.title,
            tracks = bucket.tracks,
            // Album credit belongs to the album, not to featured guests in each song.
            // Source ownership prevents merging identical album IDs across libraries.
            // Missing album identity is not evidence of a real album.
            albumCount = bucket.tracks.mapNotNull(::genreAlbumIdentity).distinct().size,
        )
    }.sortedWith(compareBy<LibraryGenre>({ it.id == UNKNOWN_GENRE_ID }, { it.title.lowercase(Locale.ROOT) }))
}

/** Stable album grouping within a genre, including compilations and multiple owners. */
private fun genreAlbumIdentity(track: Track): Triple<String, String, String>? =
    when {
        track.albumId.isNotBlank() -> Triple(track.sourceId, "id:${track.albumId}", "")
        track.album.isBlank() -> null
        else -> Triple(
            track.sourceId,
            track.album.trim().lowercase(Locale.ROOT),
            track.albumArtist.ifBlank { track.artist }.trim().lowercase(Locale.ROOT),
        )
    }

/**
 * The upstream playableTrackIds contract is keyed only by ID. If the scoped
 * genre contains two different owners/locations with the same ID, the UI
 * cannot prove which row the membership refers to. Fail closed for those rows.
 * This does not replace the owner-aware catalog authority.
 */
fun canonicalGenrePlayableTracks(
    tracks: List<Track>,
    playableTrackIds: Set<String>,
    globallyBlockedIds: Set<String> = emptySet(),
): List<Track> {
    val idCount = HashMap<String, Int>(tracks.size)
    tracks.forEach { track -> idCount[track.id] = (idCount[track.id] ?: 0) + 1 }
    return tracks.filter { it.id in playableTrackIds && idCount[it.id] == 1 &&
        it.id !in globallyBlockedIds }
}

/** Avoid highlighting a different library owner or a duplicate local location. */
fun genrePlaybackIdentityMatches(track: Track, current: Track?): Boolean =
    current != null && track.id == current.id && track.sourceId == current.sourceId &&
        (track.filepath == current.filepath || track.filepath.isBlank() || current.filepath.isBlank())

/** UI7 visual modes, independent of album UI6 selector or audio transport. */
enum class GenreViewMode(val displayName: String) {
    FEATURED("Mosaico destacado"), EDITORIAL("Editorial")
}

/** "Destacados" means most files in the current library, NEVER listening popularity. */
fun genresByTrackCount(genres: List<LibraryGenre>): List<LibraryGenre> =
    genres.sortedWith(compareBy<LibraryGenre>({ it.id == UNKNOWN_GENRE_ID },
        { -it.tracks.size }, { it.title.lowercase(Locale.ROOT) }))

/** Local, accent-insensitive label search; never queries files or remote catalogs. */
fun filterLibraryGenres(genres: List<LibraryGenre>, query: String): List<LibraryGenre> {
    val term = normalizedGenreSearch(query)
    if (term.isEmpty()) return genres
    return genres.filter { normalizedGenreSearch(it.title).contains(term) }
}

private fun normalizedGenreSearch(value: String): String =
    Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\s+"), " ")

/** Search is visible only when discovery benefits from it. */
fun showGenreSearch(totalGenres: Int, query: String): Boolean =
    totalGenres >= 8 || query.isNotEmpty()

/**
 * Columns account for actual 20dp side gutters and 10dp gap. Two cards must
 * each retain at least 170dp; layout uses the available pane, not device width.
 */
fun genreGridColumns(availableWidthDp: Int, fontScale: Float): Int {
    val textBudget = fontScale.coerceIn(1f, 3f)
    val minimumTileDp = (170 * textBudget).toInt()
    return if (availableWidthDp >= 40 + 10 + 2 * minimumTileDp) 2 else 1
}

/** Conservative static height budget at 100%-300% text scale; no animated layout. */
fun genreMosaicHeightDp(featured: Boolean, fontScale: Float): Int =
    (if (featured) 218 else 166) +
        ((fontScale.coerceIn(1f, 3f) - 1f) * (if (featured) 140 else 125)).toInt()

fun genreEditorialHeightDp(slot: GenreEditorialSlot, fontScale: Float): Int =
    slot.heightDp + ((fontScale.coerceIn(1f, 3f) - 1f) * 160).toInt()

/** Editorial composition geometry, independent of any future image asset. */
enum class GenreEditorialTreatment { COVER, PORTRAIT_LEFT, PORTRAIT_RIGHT }

data class GenreEditorialSlot(
    val span: Int,
    val heightDp: Int,
    val treatment: GenreEditorialTreatment,
)

/** Cover / double column / double column rhythm. Never implies a popularity ranking. */
fun editorialSlot(index: Int, columns: Int): GenreEditorialSlot {
    require(index >= 0 && columns >= 1)
    val treatment = when (index % 3) {
        0 -> GenreEditorialTreatment.COVER
        1 -> GenreEditorialTreatment.PORTRAIT_LEFT
        else -> GenreEditorialTreatment.PORTRAIT_RIGHT
    }
    if (columns == 1) return GenreEditorialSlot(1, 200, treatment)
    return if (treatment == GenreEditorialTreatment.COVER)
        GenreEditorialSlot(columns, 192, treatment)
    else GenreEditorialSlot(1, 246, treatment)
}
