package org.michimusic.mobile.ui.screens

import org.michimusic.core.models.Track

/**
 * UI7 Phase B phone fixture: the real 11-genre library shape.
 *
 * 33 scoped local tracks, 28 real albums, no network and no artwork inference:
 * New Wave carries 10 songs across 5 albums (so it is the single focal card and
 * the first Editorial cover), Pop carries 5 songs across 5 albums, and the nine
 * remaining genres carry 2 songs on 2 albums each. Every genre also has an
 * approved Mosaico crop and an approved Editorial poster, so layout assertions
 * exercise the real Phase B composition instead of drawn fallbacks.
 */
internal data class GenreLayoutFixture(
    val tracks: List<Track>,
    val genres: List<LibraryGenre>,
    /** Panel order: New Wave, Pop, then the remaining genres alphabetically. */
    val ordered: List<LibraryGenre>,
)

/** Genres that carry exactly two tracks in [genreLayoutFixture]. */
internal val genreLayoutTwoTrackTitles = listOf(
    "Ambient", "Blues", "Clásica", "Folk", "Jazz", "Metal", "Rock", "Soul", "Tango",
)

private const val genreLayoutSource = "local"

private fun genreLayoutTrack(
    id: String,
    title: String,
    genre: String,
    albumId: String,
    album: String,
) = Track(
    id = id,
    title = title,
    artist = "Michi QA",
    album = album,
    albumId = albumId,
    duration = 200_000,
    filepath = "content://michi/library/$id.flac",
    sourceId = genreLayoutSource,
    genre = genre,
)

internal fun genreLayoutFixture(): GenreLayoutFixture {
    val tracks = mutableListOf<Track>()
    // New Wave: 10 songs on 5 albums (two songs per album).
    repeat(10) { index ->
        tracks += genreLayoutTrack(
            id = "nw$index",
            title = "New Wave ${index + 1}",
            genre = "New Wave",
            albumId = "nw_album_${index / 2}",
            album = "New Wave Sessions ${index / 2 + 1}",
        )
    }
    // Pop: 5 songs on 5 albums.
    repeat(5) { index ->
        tracks += genreLayoutTrack(
            id = "pop$index",
            title = "Pop ${index + 1}",
            genre = "Pop",
            albumId = "pop_album_$index",
            album = "Pop Single ${index + 1}",
        )
    }
    // Nine two-track genres: 18 songs on 18 albums.
    genreLayoutTwoTrackTitles.forEachIndexed { genreIndex, genre ->
        repeat(2) { index ->
            tracks += genreLayoutTrack(
                id = "g${genreIndex}_$index",
                title = "$genre ${index + 1}",
                genre = genre,
                albumId = "genre_${genreIndex}_album_$index",
                album = "$genre Album ${index + 1}",
            )
        }
    }
    val genres = groupLibraryGenres(tracks)
    return GenreLayoutFixture(
        tracks = tracks,
        genres = genres,
        ordered = genresByTrackCount(genres),
    )
}

/** Canonical grouping key used by both production test tags. */
internal fun genreLayoutId(title: String): String = genreGroupingKey(title)

internal fun mosaicTileTag(title: String): String = "genre_tile_${genreLayoutId(title)}"

internal fun editorialTileTag(title: String): String = "genre_editorial_${genreLayoutId(title)}"

internal fun editorialPosterTag(title: String): String = "genre_editorial_poster_${genreLayoutId(title)}"

internal fun mosaicArtworkTag(title: String): String = "genre_artwork_${genreLayoutId(title)}"
