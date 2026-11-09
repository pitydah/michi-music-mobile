package org.michimusic.mobile.ui.screens

import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository
import java.util.Locale

/** A hero page backed by one real library item. No generated recommendation. */
internal enum class HomeHeroKind { TRACK, ALBUM, PLAYLIST }

internal data class HomeHeroItem(
    val key: String,
    val kind: HomeHeroKind,
    val sourceId: String,
    val title: String,
    val subtitle: String,
    val meta: String,
    val coverIds: List<String>,
    val fallbackMediaUri: String?,
    val trackIds: List<String>,
    val primaryLabel: String,
    val primaryAccessibilityLabel: String = primaryLabel,
)

/** Build the editorial carousel only from the current local library snapshot. */
internal fun buildHomeHeroItems(
    allTracks: List<Track>,
    albums: List<LocalMediaRepository.LocalAlbum>,
    playlists: List<Playlist>,
    recentIds: Set<String>,
    currentTrackId: String?,
    limit: Int = 5,
): List<HomeHeroItem> {
    if (allTracks.isEmpty() || limit <= 0) return emptyList()
    val indexed = allTracks.associateBy { it.id }
    val result = ArrayList<HomeHeroItem>(limit)
    val seenKeys = HashSet<String>()
    fun add(item: HomeHeroItem) {
        if (result.size < limit && item.trackIds.any(indexed::containsKey) && seenKeys.add(item.key)) {
            result += item
        }
    }

    val featuredId = homeFeaturedTrackId(allTracks.map { it.id }, recentIds, currentTrackId)
    val featured = featuredId?.let(indexed::get)
    if (featured != null) {
        add(
            HomeHeroItem(
                key = "track:${featured.id}",
                kind = HomeHeroKind.TRACK,
                sourceId = featured.id,
                title = featured.title,
                subtitle = featured.artist,
                meta = "${allTracks.size} ${if (allTracks.size == 1) "canción" else "canciones"} en tu colección",
                coverIds = listOf(featured.coverId),
                fallbackMediaUri = featured.filepath,
                trackIds = listOf(featured.id),
                primaryLabel = "Escuchar",
                primaryAccessibilityLabel = "Escuchar ${featured.title}",
            ),
        )
    }

    fun albumIdentity(title: String, artist: String): String =
        "${title.trim().lowercase(Locale.ROOT)}\u0000${artist.trim().lowercase(Locale.ROOT)}"
    val featuredAlbumIdentity = featured?.let { albumIdentity(it.album, it.artist) }
    albums.asSequence()
        .filter { it.tracks.isNotEmpty() }
        .distinctBy { albumIdentity(it.album.title, it.album.artist) }
        .filter { albumIdentity(it.album.title, it.album.artist) != featuredAlbumIdentity }
        .take(2)
        .forEach { album ->
            add(
                HomeHeroItem(
                    key = "album:${album.album.id}",
                    kind = HomeHeroKind.ALBUM,
                    sourceId = album.album.id,
                    title = album.album.title,
                    subtitle = album.album.artist.ifBlank { "Álbum de tu biblioteca" },
                    meta = "${album.tracks.size} ${if (album.tracks.size == 1) "canción" else "canciones"} · Álbum",
                    coverIds = listOf(album.album.coverId),
                    fallbackMediaUri = album.tracks.firstOrNull()?.filepath,
                    trackIds = album.tracks.map { it.id },
                    primaryLabel = "Escuchar",
                    primaryAccessibilityLabel = "Reproducir álbum ${album.album.title}",
                ),
            )
        }

    playlists.asSequence()
        .filter { it.trackCount > 0 && it.trackIds.any(indexed::containsKey) }
        .distinctBy { it.id }
        .take(2)
        .forEach { playlist ->
            add(
                HomeHeroItem(
                    key = "playlist:${playlist.id}",
                    kind = HomeHeroKind.PLAYLIST,
                    sourceId = playlist.id,
                    title = playlist.name,
                    subtitle = "Playlist de tu colección",
                    meta = "${playlist.trackCount} ${if (playlist.trackCount == 1) "canción" else "canciones"}",
                    coverIds = playlist.trackIds.mapNotNull { indexed[it]?.coverId }.distinct().take(4),
                    fallbackMediaUri = null,
                    trackIds = playlist.trackIds.filter(indexed::containsKey),
                    primaryLabel = "Abrir",
                    primaryAccessibilityLabel = "Abrir playlist ${playlist.name}",
                ),
            )
        }
    return result
}

/** Deterministic *local library* choices: not claims of remote recommendations. */
internal data class HomeEditorialCandidate(
    val id: String, val artist: String, val album: String = "",
)

internal fun variedHomeLibraryPickIds(
    candidates: List<HomeEditorialCandidate>,
    excludedTrackIds: Set<String>,
    limit: Int = 6,
): List<String> {
    if (limit <= 0) return emptyList()
    val eligible = candidates.asSequence().filter { it.id.isNotBlank() && it.id !in excludedTrackIds }
        .distinctBy { it.id }.toList()
    val result = ArrayList<String>(minOf(eligible.size, limit))
    val selected = HashSet<String>()
    val artists = HashSet<String>()
    val albums = HashSet<String>()
    fun albumKey(item: HomeEditorialCandidate): String =
        item.album.trim().lowercase(Locale.ROOT).ifEmpty { "unknown:${item.id}" }
    fun artistKey(item: HomeEditorialCandidate): String =
        item.artist.trim().lowercase(Locale.ROOT).ifEmpty { "unknown:${item.id}" }
    fun add(item: HomeEditorialCandidate) {
        result.add(item.id)
        selected.add(item.id)
        artists.add(artistKey(item))
        albums.add(albumKey(item))
    }
    // First pass: different artists AND albums. Never claim these are learned tastes.
    for (item in eligible) {
        if (artistKey(item) !in artists && albumKey(item) !in albums) add(item)
        if (result.size >= limit) return result
    }
    // Then admit a new album from a known artist; finally fill available space.
    for (item in eligible) {
        if (item.id !in selected && albumKey(item) !in albums) add(item)
        if (result.size >= limit) return result
    }
    for (item in eligible) {
        if (item.id !in selected) add(item)
        if (result.size >= limit) break
    }
    return result
}

/** Prefer a nonrecent, nonplaying local song to avoid duplicating the Recent carousel. */
internal fun homeFeaturedTrackId(
    availableIds: List<String>,
    recentIds: Set<String>,
    currentTrackId: String?,
): String? = availableIds.firstOrNull { it != currentTrackId && it !in recentIds }
    ?: availableIds.firstOrNull { it != currentTrackId }
    ?: availableIds.firstOrNull()
