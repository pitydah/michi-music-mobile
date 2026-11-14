package org.michimusic.mobile.ui.screens

import org.michimusic.core.models.Track
import java.util.Locale
import kotlin.math.roundToInt

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
private fun genreNames(raw: String, structured: List<String>): List<GenreToken> {
    val unique = linkedMapOf<String, GenreToken>()
    // Without structured evidence, a semicolon may be part of ONE real genre.
    // Only verified individual tags may create multiple memberships on ';'.
    // Keep NUL/US as explicit legacy field delimiters; '/' is always literal.
    // Refuse unbounded inputs before allocating split arrays.
    if (structured.isEmpty() && raw.length > 8_192) return emptyList()
    val values = if (structured.isNotEmpty()) structured else raw.split('\u0000', '\u001f')
    if (values.size > 32) return emptyList()
    if (values.sumOf(String::length) > 8_192) return emptyList()
    values.forEach { rawName ->
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
 * verified structured tags support many genres, preserving literal semicolons.
 * Legacy semicolon text is ONE ambiguous literal, not two invented genres;
 * only explicit NUL/US field delimiters are split.
 * Untagged tracks remain visible under an explicit, non-inferred bucket.
 */
fun groupLibraryGenres(tracks: List<Track>): List<LibraryGenre> {
    data class Bucket(val title: String, val tracks: MutableList<Track>)
    val buckets = linkedMapOf<String, Bucket>()
    // Avoid duplicate rows if a scope presents a track through more than one album.
    tracks.distinctBy { Triple(it.sourceId, it.id, it.filepath) }.forEach { track ->
        val names = genreNames(track.genre, track.genreValues)
            .ifEmpty { listOf(GenreToken(UNKNOWN_GENRE_ID, UNKNOWN_GENRE_TITLE)) }
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

/**
 * Mosaico focal card: the canonical 218dp hero. Larger text scales grow the card
 * by a bounded amount so the live counter (and the fallback Compose title) never
 * has to shrink below the theme's metadata/card-title size.
 */
internal const val GENRE_MOSAIC_HERO_HEIGHT_DP = 218

/** Mosaico compact card: the canonical 166dp secondary. */
internal const val GENRE_MOSAIC_SECONDARY_HEIGHT_DP = 166

/** Bounded per-scale growth for live text inside the fixed artwork cards. */
private const val GENRE_MOSAIC_TEXT_GROWTH_DP = 40

fun genreMosaicHeroHeightDp(fontScale: Float): Int =
    GENRE_MOSAIC_HERO_HEIGHT_DP +
        ((fontScale.coerceIn(1f, 3f) - 1f) * GENRE_MOSAIC_TEXT_GROWTH_DP).roundToInt()

/**
 * Compact card height. It grows with the text scale because the crop keeps 166dp
 * at 1.0x and the count chip + its gap + its inset live on the card's continuous
 * surface below the artwork band (never in a detached footer).
 */
fun genreMosaicSecondaryHeightDp(fontScale: Float): Int =
    GENRE_MOSAIC_SECONDARY_HEIGHT_DP +
        ((fontScale.coerceIn(1f, 3f) - 1f) * GENRE_MOSAIC_TEXT_GROWTH_DP / 2).roundToInt()

/** Editorial card height for one treatment, growing only with the live text. */
fun genreEditorialHeightDp(
    treatment: GenreEditorialTreatment,
    columns: Int,
    fontScale: Float,
): Int {
    val slot = editorialSlotFor(treatment, columns)
    return slot.heightDp + ((fontScale.coerceIn(1f, 3f) - 1f) * 24).roundToInt()
}

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
    return editorialSlotFor(treatment, columns)
}

/**
 * Canonical cell geometry for one Editorial treatment: a 192dp cover spanning
 * the row, a 246dp one-column portrait, and a 200dp single-column card.
 * Poster cells use the same treatment heights instead of their own asset ratio,
 * so two paired cards always share one exterior height.
 */
fun editorialSlotFor(
    treatment: GenreEditorialTreatment,
    columns: Int,
): GenreEditorialSlot {
    require(columns >= 1)
    if (columns == 1) return GenreEditorialSlot(1, 200, treatment)
    return if (treatment == GenreEditorialTreatment.COVER) {
        GenreEditorialSlot(columns, 192, treatment)
    } else {
        GenreEditorialSlot(1, 246, treatment)
    }
}
