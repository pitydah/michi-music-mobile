package org.michimusic.mobile.artist

import kotlinx.coroutines.CancellationException
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.artistIdentityAnchor

/** A confirmed credit is scoped to a *specific* library and exact local album group. */
data class ArtistProfileScope(val libraryId: String, val credit: String, val albumIds: List<String>)

enum class ArtistProfileStatus { CONFIRMED, MISSING, CONFLICT, UNRESOLVED, ERROR }

data class ArtistProfileConsensus(
    val status: ArtistProfileStatus,
    val saved: PersistedArtistProfile? = null,
    val contributingLibraries: Int = 0,
    val confirmedAssociations: List<ConfirmedArtistAssociation> = emptyList(),
) {
    val portrait: CommonsPortrait?
        get() = saved?.profile?.portrait?.takeIf(::allowedCommonsPortrait)
}

data class ConfirmedArtistAssociation(
    val scope: ArtistProfileScope,
    val saved: PersistedArtistProfile,
)

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
        chosen.copy(
            stale = profiles.any { it.stale },
            needsConfirmation = profiles.any { it.needsConfirmation },
        ),
        contributingLibraries = normalized.size,
        confirmedAssociations = normalized.zip(profiles) { scope, saved ->
            ConfirmedArtistAssociation(scope, saved)
        },
    )
}

/**
 * Selects aggregate artwork only after [resolveArtistProfileConsensus] proved that every
 * participating library confirms the same MBID. No provider is consulted. A manual local
 * selection wins, then the newest persisted revision; missing artwork in one library does
 * not invalidate the identity consensus established by all libraries.
 */
suspend fun resolveArtistArtworkConsensus(
    consensus: ArtistProfileConsensus,
    artworkStore: ArtistArtworkStore,
    slot: ArtworkSlot = ArtworkSlot.ARTIST_THUMB,
): ArtistArtworkSelection? {
    if (consensus.status != ArtistProfileStatus.CONFIRMED ||
        consensus.confirmedAssociations.isEmpty()
    ) return null
    val mbid = consensus.confirmedAssociations.first().saved.profile.mbid
    val candidates = mutableListOf<ArtistArtworkSelection>()
    for ((scope, saved) in consensus.confirmedAssociations) {
        val anchor = artistIdentityAnchor(
            scope.libraryId,
            scope.credit,
            scope.albumIds,
            saved.profile,
            saved.associationGroupKey,
        ) ?: return null
        if (anchor.confirmedMbid != mbid) return null
        val loaded = try {
            artworkStore.load(anchor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        candidates += loaded.filter {
            it.slot == slot && it.status == ArtworkLoadStatus.READY &&
                it.confirmedMbid == mbid && !it.localImagePath.isNullOrBlank()
        }
    }
    return candidates.maxWithOrNull(
        compareBy<ArtistArtworkSelection> { it.manuallyChosen }
            .thenBy { it.revision },
    )
}
