package org.michimusic.mobile.artist

import kotlinx.coroutines.CancellationException

/** A confirmed credit is scoped to a *specific* library and exact local album group. */
data class ArtistProfileScope(val libraryId: String, val credit: String, val albumIds: List<String>)

enum class ArtistProfileStatus { CONFIRMED, MISSING, CONFLICT, UNRESOLVED, ERROR }

data class ArtistProfileConsensus(
    val status: ArtistProfileStatus,
    val saved: PersistedArtistProfile? = null,
    val contributingLibraries: Int = 0,
) {
    val portrait: CommonsPortrait?
        get() = saved?.profile?.portrait?.takeIf(::allowedCommonsPortrait)
}

/**
 * No name-based inference: the only multi-library profile that can be displayed is one
 * already explicitly confirmed for EVERY contributing library, with the SAME MBID.
 * Missing/failed lookup fails closed. No MusicBrainz request occurs here.
 */
suspend fun resolveArtistProfileConsensus(
    scopes: List<ArtistProfileScope>,
    store: ArtistEnrichmentSelectionStore,
): ArtistProfileConsensus {
    val normalized = scopes.distinctBy { it.libraryId }
    if (normalized.isEmpty() || normalized.size != scopes.size ||
        normalized.map { it.credit }.distinct().size != 1 ||
        normalized.any { scopedArtistCreditKey(it.libraryId, it.credit, it.albumIds) == null }
    ) return ArtistProfileConsensus(ArtistProfileStatus.UNRESOLVED)
    val profiles = mutableListOf<PersistedArtistProfile>()
    for (scope in normalized) {
        val profile = try {
            store.load(scope.libraryId, scope.credit, scope.albumIds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ArtistProfileConsensus(ArtistProfileStatus.ERROR,
                contributingLibraries = normalized.size)
        }
        if (profile == null) return ArtistProfileConsensus(ArtistProfileStatus.MISSING, contributingLibraries = normalized.size)
        profiles += profile
    }
    if (profiles.map { it.profile.mbid }.distinct().size != 1) {
        return ArtistProfileConsensus(ArtistProfileStatus.CONFLICT, contributingLibraries = normalized.size)
    }
    val chosen = profiles.firstOrNull { it.profile.portrait?.let(::allowedCommonsPortrait) == true }
        ?: profiles.firstOrNull { !it.profile.biography.isNullOrBlank() }
        ?: profiles.first()
    return ArtistProfileConsensus(
        ArtistProfileStatus.CONFIRMED,
        chosen.copy(stale = profiles.any { it.stale }),
        contributingLibraries = normalized.size,
    )
}
