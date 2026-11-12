package org.michimusic.mobile.ui.screens

import org.michimusic.core.models.Track
import java.util.Locale
import kotlin.math.abs

/** UI7 evidence in a projection independent of Android/Room/Compose. */
data class GenreOwnerEvidence(
    val sourceId: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val filepath: String,
    val genre: String,
)

data class GenreScopedIndex(
    val genres: List<LibraryGenre>,
    val blockedTrackIds: Set<String>,
)

/** Never publish the Genres panel before the owner-collision snapshot is complete. */
sealed interface GenreIndexLoad {
    data object Loading : GenreIndexLoad
    data class Ready(
        val index: GenreScopedIndex,
        /** Stable owner/scope key that produced this index. */
        val scopeKey: String = "",
        /** Monotonic generation used to reject late results. */
        val generation: Long = 0L,
    ) : GenreIndexLoad
    data object Failed : GenreIndexLoad
}

/** One coherent Room snapshot includes rows without genre to detect duplicate IDs. */
fun cachedGenreOwnerCollisions(evidence: List<GenreOwnerEvidence>): Set<String> {
    val firstOwnerById = HashMap<String, String>(evidence.size)
    val collisions = HashSet<String>()
    evidence.forEach { row ->
        val previous = firstOwnerById.putIfAbsent(row.id, row.sourceId)
        if (previous != null && previous != row.sourceId) collisions.add(row.id)
    }
    return collisions
}

/** Pure off-main index builder: scope snapshot in, genre groups and fail-closed receipts out. */
fun buildGenreScopedIndex(
    tracks: List<Track>,
    cacheEvidence: List<GenreOwnerEvidence>,
    scopeOwnerId: String?,
    isAllScope: Boolean,
): GenreScopedIndex {
    val collisions = cachedGenreOwnerCollisions(cacheEvidence)
    val enriched = enrichGenreTracks(tracks, cacheEvidence, scopeOwnerId, isAllScope, collisions)
    return GenreScopedIndex(
        genres = groupLibraryGenres(enriched),
        blockedTrackIds = unsafeGenrePlaybackIds(enriched, collisions),
    )
}

/**
 * Join optional genre metadata into a scope-verified track snapshot. Never use
 * first matching ID; a false genre is worse than the honest Unknown bucket.
 * ownerId is the peer identity of a selected ONE library, not its display name.
 * ALL is deliberately stricter, as normalized identities currently key on id.
 */
fun enrichGenreTracks(
    tracks: List<Track>,
    evidence: List<GenreOwnerEvidence>,
    scopeOwnerId: String?,
    isAllScope: Boolean,
    collidingOwnerIds: Set<String>,
): List<Track> {
    // An ALL-cache snapshot may be much larger than this selected scope.
    // Only index candidates for tracks actually in the current collection.
    val scopedIds = tracks.mapTo(HashSet<String>(tracks.size)) { it.id }
    val byId = evidence.asSequence()
        .filter { it.id in scopedIds && it.genre.isNotBlank() }
        .groupBy { it.id }
    return tracks.map { track ->
        if (track.genre.isNotBlank()) return@map track
        // The normalized catalog can conflate owners before this UI receives it.
        // Even a selected ONE scope cannot prove which owner's metadata survived.
        if (track.id in collidingOwnerIds) return@map track
        val compatible = byId[track.id].orEmpty().filter { item ->
            if (item.title.isBlank() || !item.title.sameTag(track.title)) return@filter false
            if (item.artist.isNotBlank() && !item.artist.sameTag(track.artist)) return@filter false
            if (item.album.isNotBlank() && !item.album.sameTag(track.album)) return@filter false
            // Older sync DTOs store seconds; local/cache metadata may use ms.
            // Accept only exact millisecond evidence or the documented seconds scale.
            if (item.duration > 0 && track.duration > 0 &&
                abs(item.duration - track.duration) > 1_000L &&
                !(item.duration <= 86_400L &&
                    abs(item.duration * 1_000L - track.duration) <= 1_000L)) return@filter false
            if (track.sourceId.isNotBlank() && item.sourceId != track.sourceId)
                return@filter false
            if (scopeOwnerId != null && item.sourceId != scopeOwnerId)
                return@filter false
            val sameUri = item.filepath.isNotBlank() && item.filepath == track.filepath
            if (item.filepath.isNotBlank() && track.filepath.isNotBlank() && !sameUri)
                return@filter false
            // A remote peer without a cached path may be identified by a ONE scope
            // whose ownerId is explicit. ALL/anonymous scopes require URI evidence.
            sameUri || (!isAllScope && scopeOwnerId != null)
        }
        if (compatible.size == 1) track.copy(genre = compatible.single().genre) else track
    }
}

/** An ID collision anywhere in the active collection invalidates the ID-only
 * playback receipt even when the two tracks belong to DIFFERENT genres. */
fun unsafeGenrePlaybackIds(tracks: List<Track>, cachedOwnerCollisions: Set<String>): Set<String> {
    val seen = HashMap<String, Triple<String, String, String>>()
    val unsafe = cachedOwnerCollisions.toMutableSet()
    tracks.forEach { track ->
        val identity = Triple(track.sourceId, track.id, track.filepath)
        val previous = seen.putIfAbsent(track.id, identity)
        if (previous != null && previous != identity) unsafe.add(track.id)
    }
    return unsafe
}

/** A device MediaStore error is not a reason to hide an unrelated remote/SAF library. */
fun shouldShowGenreMediaStoreFailure(
    allScope: Boolean,
    selectedLibraryId: String?,
    deviceLibraryId: String,
): Boolean = allScope || (selectedLibraryId != null && selectedLibraryId == deviceLibraryId)

private fun String.sameTag(other: String): Boolean =
    trim().lowercase(Locale.ROOT) == other.trim().lowercase(Locale.ROOT)
