package org.michimusic.data.repository

import java.util.UUID
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedPlaylist
import org.michimusic.data.cache.LOCAL_SOURCE_ID
import org.michimusic.data.cache.PlaylistDao
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.cache.toTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fail-closed contract: callers never interpret a no-op as a persisted drag. */
sealed interface PlaylistReorderResult {
    data object Applied : PlaylistReorderResult
    data object Unchanged : PlaylistReorderResult
    data object Conflict : PlaylistReorderResult
}

enum class MoveDirection { UP, DOWN }

open class PlaylistRepository(
    private val playlistDao: PlaylistDao? = null,
    private val trackDao: TrackDao,
    private val localMediaRepository: LocalMediaRepository,
) {
    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    open suspend fun getAllPlaylists(): List<Playlist> {
        if (playlistDao == null) return emptyList()
        // The local UI is source-aware only for the phone library. Remote manifest
        // playlists stay in Room under their own sourceId until a source-aware route
        // exists; showing them here would make playlist IDs ambiguous.
        val cached = playlistDao.getAllPlaylists().filter { it.sourceId == LOCAL_SOURCE_ID }
        return cached.map {
            Playlist(
                id = it.id,
                name = it.name,
                trackIds = parseTrackIds(it.trackIds),
                trackCount = it.trackCount,
                sourceId = it.sourceId,
            )
        }
    }

    /** Local playlist lookup preserved for existing callers; no cross-source fallback. */
    open suspend fun getById(id: String): Playlist? = getByIdForSource(id, LOCAL_SOURCE_ID)

    open suspend fun getByIdForSource(id: String, sourceId: String): Playlist? {
        val cached = playlistDao?.getById(id, sourceId) ?: return null
        return Playlist(
            id = cached.id,
            name = cached.name,
            trackIds = parseTrackIds(cached.trackIds),
            trackCount = cached.trackCount,
            sourceId = cached.sourceId,
        )
    }

    open suspend fun createPlaylist(name: String, trackIds: List<String> = emptyList()): Playlist = writeMutex.withLock {
        val id = "playlist_${java.util.UUID.randomUUID().toString().take(8)}"
        val cleanIds = trackIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val entity = CachedPlaylist(
            id = id,
            name = name.trim(),
            trackIds = cleanIds.joinToString(","),
            trackCount = cleanIds.size,
        )
        playlistDao?.insert(entity)
        return@withLock Playlist(
            id = id,
            name = name.trim(),
            trackIds = cleanIds,
            trackCount = cleanIds.size,
            sourceId = LOCAL_SOURCE_ID,
        )
    }

    // New Flow-based observation of playlists
    open fun observePlaylists(): Flow<List<Playlist>> {
        return playlistDao?.observeAll()?.map { cachedList ->
            cachedList.filter { it.sourceId == LOCAL_SOURCE_ID }.map { CachedPlaylist ->
                Playlist(
                    id = CachedPlaylist.id,
                    name = CachedPlaylist.name,
                    trackIds = parseTrackIds(CachedPlaylist.trackIds),
                    trackCount = CachedPlaylist.trackCount,
                    sourceId = CachedPlaylist.sourceId,
                )
            }
        } ?: flowOf(emptyList())
    }

    // Result of adding tracks/albums to a playlist
    sealed interface PlaylistAddResult {
        data class Success(val addedCount: Int, val totalCount: Int) : PlaylistAddResult
        data object PlaylistNotFound : PlaylistAddResult
        data object EmptyTracks : PlaylistAddResult
        data class Error(val message: String) : PlaylistAddResult
    }

    // New method: add tracks to existing playlist
    open suspend fun addTracksToPlaylist(id: String, newTrackIds: List<String>): PlaylistAddResult = writeMutex.withLock {
        if (playlistDao == null) return@withLock PlaylistAddResult.Error("PlaylistDao no disponible")
        val incomingIds = newTrackIds.map { it.trim() }.filter { it.isNotBlank() }
        if (incomingIds.isEmpty()) return@withLock PlaylistAddResult.EmptyTracks

        val cached = playlistDao.getById(id, LOCAL_SOURCE_ID)
            ?: return@withLock PlaylistAddResult.PlaylistNotFound

        val existingIds = parseTrackIds(cached.trackIds)
        val combined = (existingIds + incomingIds).distinct()
        val addedCount = combined.size - existingIds.size
        playlistDao.updateTrackIdsAndCount(id, LOCAL_SOURCE_ID, combined.joinToString(","), combined.size)
        return@withLock PlaylistAddResult.Success(addedCount = addedCount, totalCount = combined.size)
    }

    // Add all tracks of an album to existing playlist
    open suspend fun addAlbumToPlaylist(id: String, albumTracks: List<Track>): PlaylistAddResult = writeMutex.withLock {
        if (playlistDao == null) return@withLock PlaylistAddResult.Error("PlaylistDao no disponible")
        if (albumTracks.isEmpty()) return@withLock PlaylistAddResult.EmptyTracks

        val cached = playlistDao.getById(id, LOCAL_SOURCE_ID)
            ?: return@withLock PlaylistAddResult.PlaylistNotFound

        val existingIds = parseTrackIds(cached.trackIds)
        val incomingIds = albumTracks.map { it.id.trim() }.filter { it.isNotBlank() }
        if (incomingIds.isEmpty()) return@withLock PlaylistAddResult.EmptyTracks

        val combined = (existingIds + incomingIds).distinct()
        val addedCount = combined.size - existingIds.size
        playlistDao.updateTrackIdsAndCount(id, LOCAL_SOURCE_ID, combined.joinToString(","), combined.size)
        return@withLock PlaylistAddResult.Success(addedCount = addedCount, totalCount = combined.size)
    }

    // New method: remove a single track from playlist
    open suspend fun removeTrackFromPlaylist(id: String, trackId: String) = writeMutex.withLock {
        if (playlistDao == null) return@withLock
        val cached = playlistDao.getById(id, LOCAL_SOURCE_ID) ?: return@withLock
        val target = trackId.trim()
        val existingIds = parseTrackIds(cached.trackIds).filter { it != target }
        playlistDao.updateTrackIdsAndCount(id, LOCAL_SOURCE_ID, existingIds.joinToString(","), existingIds.size)
    }

    open suspend fun deletePlaylist(id: String) = writeMutex.withLock {
        playlistDao?.deleteById(id, LOCAL_SOURCE_ID)
    }

    // Updates only the name column, leaving trackIds/trackCount untouched - a targeted
    // UPDATE is safer here than the read-modify-write via insert(REPLACE) used by
    // addTracksToPlaylist/removeTrackFromPlaylist, since it can't race with a concurrent
    // track list change and clobber it.
    open suspend fun renamePlaylist(id: String, newName: String) {
        if (playlistDao == null) return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        playlistDao.renameById(id, LOCAL_SOURCE_ID, trimmed)
    }

    // Moves a track one step up/down among the *resolvable* tracks only (the ones
    // PlaylistDetail can actually show), never among the raw persisted trackIds.
    //
    // Policy for unresolvable ids (4E): they never change absolute index in trackIds.
    // Only the two resolvable ids directly involved in the move swap positions with each
    // other; every other id - resolvable or not - keeps its exact slot. This is the same
    // as saying "reorder only among resolvable slots": if trackIds = [A, X, B] with X
    // unresolvable and the user moves B above A, the result is [B, X, A] - X is never
    // touched, so a currently-unresolvable remote track can't be silently dropped or
    // shuffled by a reorder it wasn't even visible for.
    //
    // A no-op (unknown playlist, trackId not currently resolvable, or already at the
    // boundary in the visible order) is always safe and never persists anything.
    /**
     * Persists one drag in one DAO update. Visible indices refer to resolvable
     * tracks; unresolved IDs retain their original absolute slots. expectedVisibleIds
     * protects against a library refresh while the user is dragging.
     * Current playlists intentionally contain unique track IDs only.
     */
    open suspend fun moveTrackToPosition(
        id: String,
        trackId: String,
        fromVisibleIndex: Int,
        toVisibleIndex: Int,
        expectedVisibleIds: List<String>,
    ): PlaylistReorderResult = writeMutex.withLock {
        val dao = playlistDao ?: return@withLock PlaylistReorderResult.Conflict
        val cached = dao.getById(id, LOCAL_SOURCE_ID) ?: return@withLock PlaylistReorderResult.Conflict
        val raw = parseTrackIds(cached.trackIds)
        val playlist = Playlist(
            id = cached.id,
            name = cached.name,
            trackIds = raw,
            trackCount = cached.trackCount,
        )
        val visible = getTracksForPlaylist(playlist).map { it.id }
        if (visible != expectedVisibleIds || visible.toSet().size != visible.size ||
            fromVisibleIndex !in visible.indices || toVisibleIndex !in visible.indices ||
            visible[fromVisibleIndex] != trackId
        ) return@withLock PlaylistReorderResult.Conflict
        if (fromVisibleIndex == toVisibleIndex) return@withLock PlaylistReorderResult.Unchanged
        val reordered = reorderedVisiblePlaylistIds(
            raw,
            visible,
            fromVisibleIndex,
            toVisibleIndex,
        ) ?: return@withLock PlaylistReorderResult.Conflict
        dao.updateTrackIds(id, LOCAL_SOURCE_ID, reordered.joinToString(","))
        PlaylistReorderResult.Applied
    }

    open suspend fun moveTrack(id: String, trackId: String, direction: MoveDirection) {
        val dao = playlistDao ?: return
        val cached = dao.getById(id, LOCAL_SOURCE_ID) ?: return
        val trackIds = parseTrackIds(cached.trackIds)
        val visible = getTracksForPlaylist(
            Playlist(
                id = cached.id,
                name = cached.name,
                trackIds = trackIds,
                trackCount = cached.trackCount,
            ),
        ).map { it.id }
        val from = visible.indexOf(trackId)
        if (from == -1) return
        val to = if (direction == MoveDirection.UP) from - 1 else from + 1
        if (to !in visible.indices) return
        // The target method re-reads the playlist under writeMutex. Any other
        // drag/arrow update between this snapshot and the commit becomes Conflict;
        // it cannot overwrite the newer persisted order.
        moveTrackToPosition(id, trackId, from, to, visible)
    }

    /**
     * Explicit result: a playlist can preserve an ID without pretending that it
     * resolved to a playable track. `ambiguous` means the same ID exists in more
     * than one cached library and no source was stored; such an ID is never
     * resolved by "first row wins".
     */
    data class PlaylistTrackResolution(
        val tracks: List<Track>,
        val unresolvedTrackIds: List<String> = emptyList(),
        val ambiguousTrackIds: List<String> = emptyList(),
        val hiddenTrackIds: List<String> = emptyList(),
    )

    /**
     * Resolves a playlist's trackIds without crossing library authority.
     *
     * - Local playlists resolve only from the active local library; if the ID is
     *   absent locally, a single cached owner may be used for a legacy playlist.
     * - Non-local playlists resolve only with the exact composite key.
     * - Multiple cached owners are ambiguous and resolve to nothing.
     * - Hidden local tracks are excluded by source + fingerprint.
     */
    open suspend fun resolvePlaylistTracks(playlist: Playlist): PlaylistTrackResolution {
        if (playlist.trackIds.isEmpty()) return PlaylistTrackResolution(emptyList())
        val sourceId = playlist.sourceId.ifBlank { LOCAL_SOURCE_ID }
        val local = sourceId == LOCAL_SOURCE_ID
        val localTracksById = if (local) {
            localMediaRepository.loadTracks().associateBy { it.id }
        } else emptyMap()
        val hidden = if (local) {
            localMediaRepository.hiddenInCurrentLibrary().associateBy { it.trackId }
        } else emptyMap()
        val activeSource = if (local) localMediaRepository.currentVisibilitySource() else sourceId
        val tracks = mutableListOf<Track>()
        val unresolved = mutableListOf<String>()
        val ambiguous = mutableListOf<String>()
        val hiddenIds = mutableListOf<String>()

        for (id in playlist.trackIds) {
            if (local) {
                val localTrack = localTracksById[id]
                if (localTrack != null) {
                    if (isLibraryTrackHidden(hidden[id], activeSource, localTrack)) {
                        hiddenIds += id
                    } else {
                        tracks += localTrack
                    }
                    continue
                }
                val cached = trackDao.getTracksById(id)
                when {
                    cached.isEmpty() -> unresolved += id
                    cached.size == 1 -> tracks += cached.single().toTrack()
                    else -> ambiguous += id
                }
            } else {
                val cached = trackDao.getTrackBySource(sourceId, id)
                if (cached == null) unresolved += id else tracks += cached.toTrack()
            }
        }
        return PlaylistTrackResolution(
            tracks = tracks,
            unresolvedTrackIds = unresolved,
            ambiguousTrackIds = ambiguous,
            hiddenTrackIds = hiddenIds,
        )
    }

    open suspend fun getTracksForPlaylist(playlist: Playlist): List<Track> =
        resolvePlaylistTracks(playlist).tracks

    // Parses the stored CSV of track IDs, trimming whitespace and dropping blanks,
    // while preserving the original stored order.
    private fun parseTrackIds(raw: String): List<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
}

// The CachedTrack projection now lives in org.michimusic.data.cache so the player
// module can read the same fields without duplicating the mapping.
