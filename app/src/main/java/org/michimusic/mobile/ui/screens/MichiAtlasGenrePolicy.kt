package org.michimusic.mobile.ui.screens

import java.net.URI
import java.util.Locale

/** Read-only projection of the authoritative, already-scoped UI7 library. */
internal data class AtlasGenreSnapshot(
    val genreId: String,
    val title: String,
    val songCount: Int,
    val albumCount: Int,
    val artists: List<String>,
)

/** A missing classification cannot become a cultural/historical entity. */
internal fun atlasGenreIsEligible(genre: LibraryGenre): Boolean =
    genre.id.isNotBlank() && !genreIsUnclassified(genre.id)

/** Bounded artist projection. Never infers history, influence, or homonymous identities. */
internal fun atlasGenreSnapshot(genre: LibraryGenre): AtlasGenreSnapshot? {
    if (!atlasGenreIsEligible(genre)) return null
    val distinctArtists = LinkedHashMap<String, String>(6)
    for (track in genre.tracks) {
        val artist = track.artist.trim()
        if (artist.isBlank()) continue
        val key = artist.lowercase(Locale.ROOT)
        if (key !in distinctArtists) distinctArtists[key] = artist
        if (distinctArtists.size >= 6) break
    }
    return AtlasGenreSnapshot(
        genreId = genre.id,
        title = genre.title,
        songCount = genre.tracks.size,
        albumCount = genre.albumCount,
        artists = distinctArtists.values.toList(),
    )
}

/** Explicit directional semantics; ASSOCIATED_WITH is NOT a genealogical claim. */
internal enum class AtlasRelationKind {
    INFLUENCE, SUBGENRE, FUSION, SCENE, // K01 compatibility; not used for curated K02 entries.
    INFLUENCED_BY, INFLUENCES, SUBGENRE_OF, FUSION_OF, ASSOCIATED_WITH,
}

internal data class AtlasSource(val title: String, val url: String)

internal data class AtlasVerifiedRelation(
    val fromGenreId: String,
    val toGenreId: String,
    val kind: AtlasRelationKind,
    val sources: List<AtlasSource>,
    val targetDisplayName: String = "",
    val explanation: String = "",
)

/** Reject malformed / insecure / hostless links rather than presenting bogus attribution. */
internal fun atlasSourceIsSafe(source: AtlasSource): Boolean {
    if (source.title.isBlank() || source.title.length > 200 || source.url.length > 2048) return false
    return try {
        val uri = URI(source.url)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.port == -1 && uri.fragment == null
    } catch (_: java.net.URISyntaxException) {
        false
    }
}

/** Fail closed on unsourced, ambiguous, invalid, self-referential and duplicate edges. */
internal fun atlasAcceptedRelations(
    genreId: String,
    proposed: List<AtlasVerifiedRelation>,
): List<AtlasVerifiedRelation> = proposed.asSequence().filter { edge ->
    edge.fromGenreId == genreId && edge.toGenreId.isNotBlank() &&
        edge.toGenreId != genreId && edge.toGenreId.length <= 96 &&
        edge.toGenreId.none { it.isISOControl() } && edge.sources.isNotEmpty() &&
        edge.sources.all(::atlasSourceIsSafe)
}.distinctBy { Triple(it.fromGenreId, it.toGenreId, it.kind) }.take(12).toList()

internal fun atlasRelationLabel(kind: AtlasRelationKind): String = when (kind) {
    AtlasRelationKind.INFLUENCED_BY -> "Influencias recibidas"
    AtlasRelationKind.INFLUENCES -> "Influyó en"
    AtlasRelationKind.SUBGENRE_OF -> "Subgénero de"
    AtlasRelationKind.FUSION_OF -> "Fusión de"
    AtlasRelationKind.ASSOCIATED_WITH -> "Escena relacionada"
    AtlasRelationKind.INFLUENCE -> "Relación de influencia"
    AtlasRelationKind.SUBGENRE -> "Relación de subgénero"
    AtlasRelationKind.FUSION -> "Relación de fusión"
    AtlasRelationKind.SCENE -> "Escena relacionada"
}

/**
 * K04: exact in-scope resolution. Never show origin songs for a visited page;
 * never query MediaStore/SAF or the network from an Atlas composable.
 * Multiple matches for the same canonical id violate the scoped index contract:
 * fail closed rather than selecting an arbitrary owner.
 */
internal fun atlasScopedGenre(
    genreId: String,
    genres: List<LibraryGenre>,
): LibraryGenre? = genres.singleOrNull { it.id == genreId && atlasGenreIsEligible(it) }

/**
 * K05: a plain playable track ID is not sufficient proof of source ownership.
 * A track can legitimately belong to multiple genres; only distinct physical
 * (source, path) identities using the SAME id make that id ambiguous. Reject
 * such ids across the entire active scope, not just inside the current genre.
 * No disk access, media scan or provider call is performed.
 */
internal fun atlasAmbiguousTrackIds(genres: List<LibraryGenre>): Set<String> {
    val firstOwner = HashMap<String, Pair<String, String>>()
    val collisions = HashSet<String>()
    for (genre in genres) {
        for (track in genre.tracks) {
            if (track.id.isBlank()) {
                collisions.add(track.id)
                continue
            }
            val owner = track.sourceId to track.filepath
            val previous = firstOwner.putIfAbsent(track.id, owner)
            if (previous != null && previous != owner) collisions.add(track.id)
        }
    }
    return collisions
}

/** Playback delegates to UI7's authoritative filter and additionally rejects cross-genre ID collisions. */
internal fun atlasScopedPlayableTracks(
    genreId: String,
    genres: List<LibraryGenre>,
    playableTrackIds: Set<String>,
    globallyBlockedTrackIds: Set<String>,
    ambiguousTrackIds: Set<String> = atlasAmbiguousTrackIds(genres),
): List<org.michimusic.core.models.Track> {
    val scoped = atlasScopedGenre(genreId, genres) ?: return emptyList()
    return canonicalGenrePlayableTracks(scoped.tracks, playableTrackIds, globallyBlockedTrackIds)
        .filter { it.id.isNotBlank() && it.id !in ambiguousTrackIds }
}

/** A bounded preview of the real files, including disabled/nonplayable rows. */
internal const val ATLAS_TRACK_PREVIEW_LIMIT = 8

internal enum class AtlasPreviewFilter { ALL, PLAYABLE_ONLY }

internal data class AtlasPlaybackSnapshot(
    val genreId: String,
    val tracks: List<org.michimusic.core.models.Track>,
    val ambiguousIds: Set<String>,
    val allPreviews: List<AtlasTrackPreview>,
    val playablePreviews: List<AtlasTrackPreview>,
)

/**
 * Build the complete scope-owned playback projection off the UI thread.
 * A null Compose result means NOT READY, never reuse an earlier owner's queue.
 * No I/O, playback mutation or MediaStore/SAF scan is performed.
 */
internal fun atlasBuildPlaybackSnapshot(
    genreId: String,
    genres: List<LibraryGenre>,
    playableTrackIds: Set<String>,
    blockedIds: Set<String>,
    ambiguousIds: Set<String> = atlasAmbiguousTrackIds(genres),
): AtlasPlaybackSnapshot {
    // K07: callers with multiple linked Atlas pages reuse one scope-owned collision index.
    // The default keeps the pure API fail-closed for standalone callers and tests.
    val collisions = ambiguousIds
    val playable = atlasScopedPlayableTracks(genreId, genres, playableTrackIds, blockedIds, collisions)
    val scoped = atlasScopedGenre(genreId, genres)
    return AtlasPlaybackSnapshot(
        genreId = genreId,
        tracks = playable,
        ambiguousIds = collisions,
        allPreviews = atlasTrackPreviews(scoped, playable, AtlasPreviewFilter.ALL),
        playablePreviews = atlasTrackPreviews(scoped, playable, AtlasPreviewFilter.PLAYABLE_ONLY),
    )
}

/**
 * Explicitly authorize an outbound playback event against the SAME current page.
 * The UI never trusts a previously saved row index after changing genre, scope,
 * or permission. A pending/null projection cannot authorize playback.
 */
internal fun atlasMayDispatch(
    snapshot: AtlasPlaybackSnapshot?,
    currentGenreId: String,
    playbackIndex: Int?,
    canPlayLocally: Boolean,
): Boolean = snapshot != null && snapshot.genreId == currentGenreId &&
    canPlayLocally && playbackIndex != null && playbackIndex in snapshot.tracks.indices

internal data class AtlasTrackPreview(
    val track: org.michimusic.core.models.Track,
    val playbackIndex: Int?,
)

internal fun atlasTrackPreviews(
    genre: LibraryGenre?,
    playableTracks: List<org.michimusic.core.models.Track>,
    filter: AtlasPreviewFilter = AtlasPreviewFilter.ALL,
): List<AtlasTrackPreview> {
    if (genre == null) return emptyList()
    val positions = playableTracks.withIndex().associate { (index, track) ->
        Triple(track.sourceId, track.id, track.filepath) to index
    }
    return genre.tracks.asSequence()
        .distinctBy { Triple(it.sourceId, it.id, it.filepath) }
        .filter { track -> filter == AtlasPreviewFilter.ALL ||
            Triple(track.sourceId, track.id, track.filepath) in positions }
        .take(ATLAS_TRACK_PREVIEW_LIMIT)
        .map { track ->
            AtlasTrackPreview(track, positions[Triple(track.sourceId, track.id, track.filepath)])
        }.toList()
}

/** K03: maximum eight distinct editorial stops; no duplicate trail or unverified hop. */
internal const val ATLAS_MAX_ROUTE_DEPTH = 8

/**
 * Pure, bounded navigation. The current page must own the accepted directed edge,
 * the destination must have a published page, and a cycle rewinds the existing
 * trail instead of allocating a second copy of the same cultural node.
 */
internal fun atlasAdvanceRoute(
    trail: List<String>,
    fromGenreId: String,
    edge: AtlasVerifiedRelation,
    destinationPublished: Boolean,
): List<String>? {
    if (!destinationPublished || trail.isEmpty() || trail.size > ATLAS_MAX_ROUTE_DEPTH ||
        trail.last() != fromGenreId || edge.fromGenreId != fromGenreId ||
        atlasAcceptedRelations(fromGenreId, listOf(edge)).isEmpty()
    ) return null
    val backIndex = trail.indexOf(edge.toGenreId)
    if (backIndex >= 0) return trail.take(backIndex + 1)
    if (trail.size >= ATLAS_MAX_ROUTE_DEPTH) return null
    return trail + edge.toGenreId
}

/** Jump only to an already-visited route stop. No arbitrary genre navigation. */
internal fun atlasRewindRoute(trail: List<String>, genreId: String): List<String>? {
    val index = trail.indexOf(genreId)
    return if (index < 0 || index >= trail.size || trail.size > ATLAS_MAX_ROUTE_DEPTH) null
        else trail.take(index + 1)
}

/** Null at the origin means the containing GenreLibraryPanel owns the back action. */
internal fun atlasPreviousRoute(trail: List<String>): List<String>? =
    if (trail.size > 1) trail.dropLast(1) else null
