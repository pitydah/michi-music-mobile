package org.michimusic.mobile.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import org.michimusic.core.models.Album
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.MusicLibrary
import org.michimusic.core.models.ResolvedTrack
import org.michimusic.data.repository.LegacyTrackMapper
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum
import org.michimusic.data.repository.MusicLibraryRepository
import java.security.MessageDigest

/** One authoritative library snapshot for artists, albums, playback queues and profiles. */
data class ArtistScopedCollection(
    val ready: Boolean,
    val albums: List<LocalAlbum>,
    val identityLibraryId: String?,
    val scope: LibraryScope,
    val errorMessage: String? = null,
    /** A playback permission/URI snapshot, not an assertion that a future stream will succeed. */
    val playableTrackIds: Set<String> = emptySet(),
)

private sealed interface ArtistScopeRead {
    val scope: LibraryScope
    data class Loading(override val scope: LibraryScope) : ArtistScopeRead
    data class Ready(override val scope: LibraryScope, val tracks: List<ResolvedTrack>) : ArtistScopeRead
    data class Error(override val scope: LibraryScope) : ArtistScopeRead
}

/**
 * A scope change creates a new state producer with an empty initial state, so that
 * old album associations cannot survive while the new Room membership loads.
 */
@Composable
fun rememberArtistScopedCollection(
    localAlbums: List<LocalAlbum>,
    scope: LibraryScope,
    libraries: List<MusicLibrary>,
    repository: MusicLibraryRepository,
    retryKey: Int = 0,
): ArtistScopedCollection {
    val observed by produceState<ArtistScopeRead>(
        initialValue = ArtistScopeRead.Loading(scope), key1 = scope, key2 = repository, key3 = retryKey,
    ) {
        try {
            repository.observeTracks(scope).flowOn(Dispatchers.IO).collect { tracks ->
                value = ArtistScopeRead.Ready(scope, tracks)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = ArtistScopeRead.Error(scope)
        }
    }
    return remember(localAlbums, scope, libraries, observed) {
        when (val current = observed) {
            is ArtistScopeRead.Ready -> if (current.scope == scope)
                resolveArtistScopedCollection(localAlbums, scope, libraries, current.tracks)
                else ArtistScopedCollection(false, emptyList(), null, scope)
            is ArtistScopeRead.Error -> ArtistScopedCollection(false, emptyList(), null, scope,
                "No se pudo leer la biblioteca. Comprueba el almacenamiento e inténtalo de nuevo.")
            is ArtistScopeRead.Loading -> ArtistScopedCollection(false, emptyList(), null, scope)
        }
    }
}

/**
 * Membership is authoritative for ONE: never fall back to unrelated MediaStore tracks.
 * In ALL, retain unregistered MediaStore tracks for backward compatibility.
 * Matched tracks preserve local codec, year and artwork; repository-only tracks are
 * represented by the pre-existing LegacyTrackMapper, without invented artwork.
 */
fun resolveArtistScopedCollection(
    localAlbums: List<LocalAlbum>,
    scope: LibraryScope,
    libraries: List<MusicLibrary>,
    resolvedTracks: List<ResolvedTrack>,
): ArtistScopedCollection {
    val localById = localAlbums.flatMap { it.tracks }.associateBy { it.id }
    // A registered identity is allowed to play only through the repository's
    // CURRENT preferred location. Do not reuse a stale MediaStore path when the
    // location is OFFLINE, unavailable or requires permission.
    val registered = resolvedTracks.distinctBy { it.identity.id }
    val registeredIds = registered.map { it.identity.id }.toSet()
    val matched = registered.map { resolved ->
        val mapped = LegacyTrackMapper.toLegacyTrack(resolved)
        val original = localById[resolved.identity.id]
        if (original != null) {
            // Preserve artwork/codec/year, but take URI + source from the verified
            // location; blank the URI when the registered track has no route.
            original.copy(filepath = mapped.filepath, source = mapped.source)
        } else mapped
    }
    // Old MediaStore items may not yet have canonical registration. Only ALL
    // includes them, and only plausible Android/HTTP URIs may be queued.
    val legacyOnly = if (scope is LibraryScope.All)
        localById.values.filter { it.id !in registeredIds } else emptyList()
    val tracks = (matched + legacyOnly).distinctBy { it.id }
    val playableIds = registered.filter { it.preferredLocation?.uri?.isNotBlank() == true }
        .map { it.identity.id }.toMutableSet()
    playableIds += legacyOnly.filter { artistLegacyUriIsPlayableCandidate(it.filepath) }
        .map { it.id }
    // The *album ID*, not only title/credit, defines a distinct album. Different
    // releases with the same visible title must not collapse into one detail route.
    // Prefer the local track membership when legacy MediaStore leaves track.albumId blank.
    val localGroupsByTrack = localAlbums.flatMap { local ->
        local.tracks.map { track -> track.id to local }
    }.groupBy({ it.first }, { it.second })
    // Compute signatures once per snapshot; repeated per-row sorting would be O(m²).
    val groupKeys = java.util.IdentityHashMap<LocalAlbum, String>()
    localAlbums.forEach { group ->
        val fields = listOf(group.album.id, group.album.title, group.album.artist) +
            group.tracks.map { it.id }.distinct().sorted()
        groupKeys[group] = fields.joinToString("") { "${it.length}:$it" }
    }
    fun localGroupKey(group: LocalAlbum): String = checkNotNull(groupKeys[group])
    fun localGroupFor(track: org.michimusic.core.models.Track): LocalAlbum? =
        localGroupsByTrack[track.id]?.distinctBy(::localGroupKey)?.singleOrNull()
    // The credit fallback is safe only when exactly one local album has that credit.
    val localByCredit = localAlbums.groupBy { group ->
        val first = group.tracks.firstOrNull()
        (first?.album ?: group.album.title) to (first?.artist ?: group.album.artist)
    }
    val scopedAlbums = tracks.groupBy { track ->
        // Use the complete local-group membership, not just MediaStore's reused album ID.
        val local = localGroupFor(track)
        val groupKey = local?.let(::localGroupKey)
            ?: track.albumId.takeIf(String::isNotBlank).orEmpty()
        groupKey to ((local?.album?.title ?: track.album) to (local?.album?.artist ?: track.artist))
    }.map { (key, groupTracks) ->
        val (knownId, credit) = key
        val original = groupTracks.mapNotNull(::localGroupFor)
            .distinctBy(::localGroupKey).singleOrNull()?.album
            ?: localByCredit[credit]?.singleOrNull()?.album
        val album = original?.copy(trackCount = groupTracks.size) ?: Album(
            id = knownId.ifBlank { "artist_scope_${stableAlbumKey(credit.first, credit.second)}" },
            title = credit.first.ifBlank { "Álbum desconocido" },
            artist = credit.second,
            year = groupTracks.firstOrNull { it.year > 0 }?.year ?: 0,
            trackCount = groupTracks.size,
            coverId = groupTracks.firstOrNull { it.coverId.isNotBlank() }?.coverId.orEmpty(),
        )
        LocalAlbum(album, groupTracks)
    }.sortedWith(compareBy({ it.album.artist.lowercase() }, { it.album.title.lowercase() }))
    // All with only device-local tracks is not ambiguous any more: Artist Enrichment is a
    // local-library feature, so the canonical device library owns the identity. A mix with
    // synced/streaming tracks keeps the previous conservative null until the user picks one.
    val identityLibraryId = when (scope) {
        is LibraryScope.One -> scope.libraryId.takeIf { id -> libraries.any { it.id == id } }
        LibraryScope.All -> {
            val hasRemoteTracks = tracks.any { it.source != org.michimusic.core.models.TrackSource.LOCAL }
            if (tracks.isNotEmpty() && !hasRemoteTracks) {
                org.michimusic.data.cache.MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
            } else {
                libraries.singleOrNull()?.id
            }
        }
    }
    return ArtistScopedCollection(true, scopedAlbums, identityLibraryId, scope,
        playableTrackIds = playableIds)
}

private fun stableAlbumKey(title: String, credit: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$title\u0000$credit".toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Strictly a legacy fallback for unregistered MediaStore items; not file-existence proof. */
fun artistLegacyUriIsPlayableCandidate(uri: String): Boolean {
    val trimmed = uri.trim()
    if (trimmed.isEmpty()) return false
    return trimmed.startsWith("/") ||
        listOf("content://", "file://", "https://", "http://")
            .any { trimmed.startsWith(it, ignoreCase = true) }
}
