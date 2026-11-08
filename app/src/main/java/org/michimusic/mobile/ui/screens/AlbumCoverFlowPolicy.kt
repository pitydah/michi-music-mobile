package org.michimusic.mobile.ui.screens

import org.michimusic.core.models.LibraryScope
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum
import org.michimusic.mobile.library.coverflow.CoverFlowAlbum

internal data class AlbumCoverFlowPresentation(
    val albums: List<CoverFlowAlbum>,
    val albumById: Map<String, LocalAlbum>,
)

/** Maps scoped albums to stable, unambiguous CoverFlow identities. */
internal fun albumCoverFlowPresentation(
    albums: List<LocalAlbum>,
    scope: LibraryScope,
): AlbumCoverFlowPresentation {
    val identityCounts = albumNavigationIdentityCounts(albums, scope)
    val albumById = albums.mapNotNull { album ->
        val id = albumNavigationIdentity(album, scope)
        if (identityCounts[id] == 1) id to album else null
    }.toMap()
    val coverFlowAlbums = albums.mapNotNull { album ->
        val id = albumNavigationIdentity(album, scope)
        if (id !in albumById) return@mapNotNull null
        CoverFlowAlbum(
            id = id,
            title = album.album.title,
            artist = album.album.artist,
            year = album.album.year,
            trackCount = album.tracks.size,
            hasArt = true,
            coverUri = album.album.coverId,
            sourceAudioUri = album.tracks.firstOrNull()?.filepath.orEmpty(),
        )
    }
    return AlbumCoverFlowPresentation(coverFlowAlbums, albumById)
}
