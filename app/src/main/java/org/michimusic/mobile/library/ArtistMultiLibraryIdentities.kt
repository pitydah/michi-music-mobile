package org.michimusic.mobile.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.MusicLibrary
import org.michimusic.core.models.ResolvedTrack
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.mobile.artist.ArtistProfileScope

/** Reuse a single shared membership observer for the entire grid, not one per portrait tile. */
data class ArtistIdentityMemberships(
    val ready: Boolean,
    val byCredit: Map<String, List<ArtistProfileScope>>,
    val unscopedCredits: Set<String> = emptySet(),
    val error: Boolean = false,
)

fun resolveArtistIdentityMemberships(
    aggregate: ArtistScopedCollection,
    localAlbums: List<LocalAlbum>,
    libraries: List<MusicLibrary>,
    tracksByLibrary: Map<String, List<ResolvedTrack>>,
): ArtistIdentityMemberships {
    if (!aggregate.ready) return ArtistIdentityMemberships(false, emptyMap())
    val visibleTracks = aggregate.albums.flatMap { it.tracks }.associateBy { it.id }
    val contributors = mutableMapOf<String, MutableList<ArtistProfileScope>>()
    val coveredTrackIds = mutableSetOf<String>()
    for (library in libraries) {
        val records = tracksByLibrary[library.id] ?: return ArtistIdentityMemberships(false, emptyMap())
        val one = resolveArtistScopedCollection(localAlbums, LibraryScope.One(library.id), libraries, records)
        for ((credit, albums) in one.albums.groupBy { it.album.artist }) {
            val visible = albums.flatMap { it.tracks }.map { it.id }.filter { it in visibleTracks }
            if (visible.isEmpty()) continue
            coveredTrackIds.addAll(visible)
            contributors.getOrPut(credit) { mutableListOf() } += ArtistProfileScope(
                libraryId = library.id,
                credit = credit,
                albumIds = albums.map { it.album.id }.distinct().sorted(),
            )
        }
    }
    // Legacy MediaStore tracks not registered with a library have no provable scoped key.
    val unsupported = visibleTracks.values.filter { it.id !in coveredTrackIds }.map { it.artist }.toSet()
    return ArtistIdentityMemberships(
        ready = true,
        byCredit = contributors.filterKeys { it !in unsupported },
        unscopedCredits = unsupported,
    )
}

@Composable
fun rememberArtistIdentityMemberships(
    aggregate: ArtistScopedCollection,
    localAlbums: List<LocalAlbum>,
    libraries: List<MusicLibrary>,
    repository: MusicLibraryRepository,
    retryKey: Int = 0,
): ArtistIdentityMemberships {
    val identities = libraries.map { it.id }.sorted()
    val observed by produceState(
        initialValue = ArtistIdentityMemberships(false, emptyMap()),
        key1 = aggregate.scope, key2 = identities, key3 = repository,
        key4 = aggregate to retryKey,
    ) {
        if (aggregate.scope !is LibraryScope.All || libraries.size < 2) {
            value = ArtistIdentityMemberships(true, emptyMap())
            return@produceState
        }
        try {
            val flows = libraries.map { library ->
                repository.observeTracks(LibraryScope.One(library.id))
                    .map { tracks -> library.id to tracks }
            }
            combine(flows) { pairs -> pairs.toMap() }
                .flowOn(Dispatchers.IO)
                .collect { records ->
                    value = resolveArtistIdentityMemberships(aggregate, localAlbums, libraries, records)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Missing or failing membership data must not leak a portrait from another library.
            value = ArtistIdentityMemberships(true, emptyMap(), error = true)
        }
    }
    return observed
}
