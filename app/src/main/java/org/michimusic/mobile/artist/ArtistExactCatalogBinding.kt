package org.michimusic.mobile.artist

import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum

/** D34. The only track key shared by the mosaic and exact artist detail.
 * It remains in memory: sourceId / filepath must NEVER be added to a route.
 */
internal fun exactArtistTrackIdentity(track: Track): String =
    "${track.sourceId}\u0000${track.id}\u0000${track.filepath}"

/** Mirrors Biblioteca -> Artistas: the album artist is a fallback only for a blank track credit. */
internal fun exactArtistCatalogOccurrences(
    albums: List<LocalAlbum>, libraryId: String?, confirmedMbids: Map<String, String?> = emptyMap(),
): List<ArtistMembershipOccurrence> = albums.flatMap { group ->
    val albumId = group.album.id
    val albumCredit = group.album.artist
    if (group.tracks.isEmpty()) listOf(
        ArtistMembershipOccurrence(
            libraryId = libraryId, albumId = albumId, literalCredit = albumCredit,
            trackIdentity = "$albumId#album", verifiedMbid = confirmedMbids[albumCredit.trim()],
        )
    ) else group.tracks.map { track ->
        val credit = track.artist.ifBlank { albumCredit }
        ArtistMembershipOccurrence(
            libraryId = libraryId, albumId = albumId, literalCredit = credit,
            trackIdentity = exactArtistTrackIdentity(track),
            verifiedMbid = confirmedMbids[credit.trim()],
        )
    }
}

/** Read-only membership selection. Never hands a guest performer every track on an album.
 * Rejects ambiguous album IDs, duplicate keys, missing rows and foreign credits.
 */
internal fun exactArtistCatalogAlbums(
    available: List<LocalAlbum>, snapshot: ArtistExactMembershipSnapshot,
): List<LocalAlbum>? {
    if (snapshot.members.isEmpty() || available.map { it.album.id }.distinct().size != available.size) return null
    val indexed = available.associateBy { it.album.id }
    val used = HashSet<Pair<String, String>>()
    val selected = mutableMapOf<String, MutableList<Track>>()
    val albumOnly = HashSet<String>()
    for (member in snapshot.members) {
        val group = indexed[member.albumId] ?: return null
        if (member.literalCredit !in snapshot.mergedCredits) return null
        val key = member.albumId to member.trackIdentity
        if (!used.add(key)) return null
        if (group.tracks.isEmpty()) {
            if (member.trackIdentity != "${member.albumId}#album" ||
                member.literalCredit != group.album.artist || !albumOnly.add(member.albumId)
            ) return null
        } else {
            val matched = group.tracks.filter { track ->
                exactArtistTrackIdentity(track) == member.trackIdentity &&
                    track.artist.ifBlank { group.album.artist } == member.literalCredit
            }
            if (matched.size != 1) return null
            selected.getOrPut(member.albumId) { mutableListOf() }.add(matched.single())
        }
    }
    if (used.size != snapshot.members.size || used.map { it.first }.toSet() != snapshot.albumIds.toSet()) return null
    // A detail list addressed only by track.id may otherwise silently lose a duplicate.
    val chosen = selected.values.flatten()
    if (chosen.map { it.id }.distinct().size != chosen.size) return null
    return snapshot.albumIds.map { id ->
        val group = indexed[id] ?: return null
        val tracks = selected[id].orEmpty()
        if (tracks.isEmpty() && id !in albumOnly) return null
        group.copy(album = group.album.copy(trackCount = tracks.size), tracks = tracks)
    }
}
