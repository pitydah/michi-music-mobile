package org.michimusic.mobile.screens

import org.michimusic.core.metadata.MetadataCommittedLibraryChange
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.repository.MetadataCommittedTrackProjection

/**
 * Build a published catalog only on a confirmed library load, not on each query.
 * Source + owning library + ID + location identify a playable row; metadata
 * must never decide whether an audio source is discarded. Never collapse rows
 * from two Michi Link libraries merely because their server-local IDs coincide.
 */
internal data class SearchTrackIdentity(
    val source: TrackSource,
    val sourceId: String,
    val id: String,
    val filepath: String,
)

internal fun searchTrackIdentity(track: Track): SearchTrackIdentity = SearchTrackIdentity(
    track.source, track.sourceId, track.id, track.filepath,
)

internal fun buildSearchCatalogSnapshot(local: List<Track>, synced: List<Track>): List<Track> =
    (local.asSequence() + synced.asSequence()).distinctBy(::searchTrackIdentity).toList()

/** React to a genuine Room sync change without querying MediaStore/SAF a second time.
 * Keep the LOCAL part of the *same published root*, replacing only SYNCED rows.
 */
internal fun replaceSearchSyncedTracks(
    previous: SearchCatalogLoad.Ready,
    synced: List<Track>,
): List<Track> = buildSearchCatalogSnapshot(
    previous.tracks.filterNot { it.source == TrackSource.SYNCED }, synced,
)

/**
 * Applies one exact committed revision to an already published catalog.
 * Returns null when no row matches; never inserts, reorders or scans a provider.
 */
internal fun applyMetadataCommittedToSearchCatalog(
    tracks: List<Track>,
    change: MetadataCommittedLibraryChange,
): List<Track>? {
    var applied = false
    val updated = tracks.map { track ->
        when (val result = MetadataCommittedTrackProjection.apply(track, change)) {
            is MetadataCommittedTrackProjection.Result.Applied -> {
                applied = true
                result.track
            }
            is MetadataCommittedTrackProjection.Result.Refused -> track
        }
    }
    return if (applied) updated else null
}

/**
 * A catalog is executable only after its own root/load generation completed.
 * begin() revokes earlier results synchronously, including when a later load
 * fails or is cancelled. A superseded load cannot publish its old snapshot.
 */
internal sealed interface SearchCatalogLoad {
    val generation: Long
    data object Unloaded : SearchCatalogLoad { override val generation: Long = 0L }
    data class Loading(override val generation: Long) : SearchCatalogLoad
    data class Ready(override val generation: Long, val tracks: List<Track>) : SearchCatalogLoad
    data class Failed(override val generation: Long, val reason: String) : SearchCatalogLoad
}

internal class SearchCatalogGate {
    private val publication = java.util.concurrent.atomic.AtomicReference<SearchCatalogLoad>(
        SearchCatalogLoad.Unloaded,
    )

    fun current(): SearchCatalogLoad = publication.get()

    // Multiple root revisions must acquire ordered tickets even if called concurrently.
    @Synchronized fun begin(): SearchCatalogLoad.Loading =
        SearchCatalogLoad.Loading(publication.get().generation + 1L).also(publication::set)

    fun complete(ticket: SearchCatalogLoad.Loading, tracks: List<Track>): Boolean =
        publication.compareAndSet(ticket, SearchCatalogLoad.Ready(ticket.generation, tracks))

    fun fail(ticket: SearchCatalogLoad.Loading, reason: String): Boolean =
        publication.compareAndSet(ticket, SearchCatalogLoad.Failed(ticket.generation, reason))
}
