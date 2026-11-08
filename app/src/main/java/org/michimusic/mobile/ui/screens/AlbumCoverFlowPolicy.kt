package org.michimusic.mobile.ui.screens

import java.security.MessageDigest
import java.util.Locale
import org.michimusic.core.models.LibraryScope
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum
import org.michimusic.mobile.library.coverflow.CoverFlowAlbum

internal data class AlbumCoverFlowPresentation(
    val albums: List<CoverFlowAlbum>,
    val albumById: Map<String, LocalAlbum>,
    val continuityKeyById: Map<String, String?>,
)

internal data class CoverFlowSelection(
    val id: String,
    val continuityKey: String?,
)

/** Stable only for carousel continuity; navigation identity remains snapshot-specific. */
private fun albumCoverFlowContinuityKey(album: LocalAlbum, scope: LibraryScope): String {
    val scopeToken = when (scope) {
        LibraryScope.All -> "all"
        is LibraryScope.One -> "one:${scope.libraryId}"
    }
    val fields = listOf(
        "michi-album-coverflow-continuity-v1",
        scopeToken,
        album.album.id,
        album.album.title.trim().lowercase(Locale.ROOT),
        album.album.artist.trim().lowercase(Locale.ROOT),
        album.album.year.toString(),
    )
    val material = fields.joinToString("") { "${it.length}:$it" }
    val hex = MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "albc1_$hex"
}

/** Maps scoped albums to stable, unambiguous CoverFlow identities. */
internal fun albumCoverFlowPresentation(
    albums: List<LocalAlbum>,
    scope: LibraryScope,
): AlbumCoverFlowPresentation {
    val identityCounts = albumNavigationIdentityCounts(albums, scope)
    val continuityCounts = albums.groupingBy { albumCoverFlowContinuityKey(it, scope) }.eachCount()
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
    val continuityKeyById = albumById.mapValues { (_, album) ->
        albumCoverFlowContinuityKey(album, scope).takeIf { continuityCounts[it] == 1 }
    }
    return AlbumCoverFlowPresentation(coverFlowAlbums, albumById, continuityKeyById)
}

internal fun coverFlowSelection(
    id: String,
    presentation: AlbumCoverFlowPresentation,
): CoverFlowSelection? = id.takeIf(presentation.albumById::containsKey)?.let {
    CoverFlowSelection(it, presentation.continuityKeyById[it])
}

internal fun reconcileCoverFlowSelection(
    selection: CoverFlowSelection?,
    presentation: AlbumCoverFlowPresentation,
    refreshInProgress: Boolean,
): CoverFlowSelection? {
    if (presentation.albums.isEmpty()) return if (refreshInProgress) selection else null
    selection?.id?.let { id -> coverFlowSelection(id, presentation)?.let { return it } }
    selection?.continuityKey?.let { key ->
        val matchingIds = presentation.continuityKeyById.filterValues { it == key }.keys
        if (matchingIds.size == 1) return coverFlowSelection(matchingIds.single(), presentation)
    }
    return coverFlowSelection(presentation.albums.first().id, presentation)
}
