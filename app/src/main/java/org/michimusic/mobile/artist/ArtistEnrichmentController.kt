package org.michimusic.mobile.artist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.artistIdentityAnchor

/** The only visible state of the enrichment journey for one local artist. */
enum class ArtistEnrichmentStatus {
    IDLE,
    SEARCHING,
    CHOOSING,
    VERIFIED,
    FAILED,
}

data class ArtistEnrichmentState(
    val status: ArtistEnrichmentStatus = ArtistEnrichmentStatus.IDLE,
    val profile: ArtistExternalProfile? = null,
    val candidates: List<ArtistLookupCandidate> = emptyList(),
    val persisted: Boolean = false,
    val stale: Boolean = false,
    val refreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val message: String? = null,
    val pendingPortraits: List<ArtistArtworkCandidate> = emptyList(),
    val selections: Map<ArtworkSlot, ArtistArtworkSelection> = emptyMap(),
)

/**
 * A provider failure must not erase images already verified for the same identity.
 * Only an explicit identity change (or an explicit clear) may replace the whole map.
 */
internal fun mergeEnrichmentSelections(
    current: Map<ArtworkSlot, ArtistArtworkSelection>,
    incoming: Map<ArtworkSlot, ArtistArtworkSelection>,
    identityChanged: Boolean,
): Map<ArtworkSlot, ArtistArtworkSelection> =
    if (identityChanged) incoming else current + incoming

/**
 * Single owner of identity, biography and artwork work. It keeps the last verified
 * profile visible while a refresh is running and never confirms an identity by name.
 * Detail and panel observe the same state instead of launching their own pipeline.
 */
class ArtistEnrichmentController(
    private val orchestrator: ArtistEnrichmentOrchestrator,
    private val profileStore: ArtistEnrichmentSelectionStore,
    private val artworkStore: ArtistArtworkStore,
    private val verifySelection: suspend (ArtistArtworkSelection) -> ArtistArtworkSelection,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ArtistEnrichmentState())
    val state: StateFlow<ArtistEnrichmentState> = _state.asStateFlow()

    private var bindKey: String? = null
    private var requestJob: Job? = null
    private var autoDiscoveryAllowed = true

    /**
     * Identity-scoped request epoch. A response from a previous artist, library or
     * explicit action is discarded instead of overwriting the current screen.
     */
    private var requestEpoch = 0

    fun bind(libraryId: String?, artistCredit: String, albumIds: List<String>) {
        val key = listOf(
            libraryId.orEmpty(),
            artistCredit,
            albumIds.toSortedSet().joinToString("\u0000"),
        ).joinToString("\u0001")
        if (key == bindKey) return
        bindKey = key
        requestJob?.cancel()
        autoDiscoveryAllowed = true
        val epoch = ++requestEpoch
        _state.value = ArtistEnrichmentState()
        requestJob = scope.launch {
            bootstrap(libraryId, artistCredit, albumIds, epoch)
        }
    }

    /** Re-runs discovery only after an explicit user action or a fresh bind. */
    fun discover(artistCredit: String) {
        if (!autoDiscoveryAllowed) return
        requestJob?.cancel()
        val epoch = ++requestEpoch
        _state.value = _state.value.copy(
            status = ArtistEnrichmentStatus.SEARCHING,
            candidates = emptyList(),
            message = null,
        )
        requestJob = scope.launch {
            try {
                val found = orchestrator.discover(artistCredit)
                if (epoch != requestEpoch) return@launch
                _state.value = _state.value.copy(
                    status = if (found.isEmpty()) ArtistEnrichmentStatus.FAILED
                    else ArtistEnrichmentStatus.CHOOSING,
                    candidates = found,
                    message = if (found.isEmpty()) "No se encontraron coincidencias verificables." else null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch != requestEpoch) return@launch
                _state.value = _state.value.copy(
                    status = ArtistEnrichmentStatus.FAILED,
                    message = "No fue posible consultar MusicBrainz.",
                )
            }
        }
    }

    /** Only an explicit selection can turn a candidate into a confirmed MBID. */
    fun confirm(
        artistCredit: String,
        libraryId: String?,
        albumIds: List<String>,
        mbid: String,
    ) {
        requestJob?.cancel()
        val epoch = ++requestEpoch
        _state.value = _state.value.copy(
            status = ArtistEnrichmentStatus.VERIFIED,
            candidates = emptyList(),
            refreshing = true,
            refreshFailed = false,
            message = null,
        )
        requestJob = scope.launch {
            val previous = _state.value.profile
            try {
                val result = orchestrator.enrich(
                    artistCredit = artistCredit,
                    libraryId = libraryId,
                    albumIds = albumIds,
                    mbid = mbid,
                    cached = previous,
                    forceRefresh = true,
                )
                if (epoch != requestEpoch) return@launch
                apply(result, previous, epoch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch != requestEpoch) return@launch
                _state.value = _state.value.copy(
                    status = if (previous == null) ArtistEnrichmentStatus.FAILED
                    else ArtistEnrichmentStatus.VERIFIED,
                    refreshing = false,
                    refreshFailed = true,
                    message = "No fue posible actualizar los datos externos.",
                )
            }
        }
    }

    /** Explicit retry keeps the verified profile visible while the refresh runs. */
    fun refresh(artistCredit: String, libraryId: String?, albumIds: List<String>, force: Boolean = true) {
        val current = _state.value
        val profile = current.profile ?: return
        requestJob?.cancel()
        val epoch = ++requestEpoch
        _state.value = current.copy(refreshing = true, refreshFailed = false, message = null)
        requestJob = scope.launch {
            try {
                val needsProfile = force || current.stale || profile.biography.isNullOrBlank()
                val result = if (needsProfile) {
                    orchestrator.enrich(
                        artistCredit = artistCredit,
                        libraryId = libraryId,
                        albumIds = albumIds,
                        mbid = profile.mbid,
                        cached = profile,
                        forceRefresh = force || current.stale,
                    )
                } else {
                    orchestrator.resolveArtwork(
                        artistCredit = artistCredit,
                        libraryId = libraryId,
                        albumIds = albumIds,
                        profile = profile,
                    )
                }
                if (epoch != requestEpoch) return@launch
                apply(result, profile, epoch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch != requestEpoch) return@launch
                _state.value = _state.value.copy(
                    refreshing = false,
                    refreshFailed = true,
                    message = "No fue posible actualizar los datos externos.",
                )
            }
        }
    }

    /**
     * Removes the association and cancels in-flight work. The following automatic pass
     * is suppressed; only a new bind or an explicit discovery can start it again.
     */
    fun clear(artistCredit: String, libraryId: String?, albumIds: List<String>) {
        requestJob?.cancel()
        ++requestEpoch
        autoDiscoveryAllowed = false
        val anchor = _state.value.profile?.let {
            artistIdentityAnchor(libraryId, artistCredit, albumIds, it)
        }
        _state.value = ArtistEnrichmentState(
            status = ArtistEnrichmentStatus.IDLE,
            message = "Enriquecimiento borrado.",
        )
        requestJob = scope.launch {
            try {
                if (libraryId != null) profileStore.clear(libraryId, artistCredit, albumIds)
                if (anchor != null) artworkStore.clear(anchor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    message = "No fue posible borrar todos los datos guardados.",
                )
            }
        }
    }

    /**
     * A manual choice is persisted by the artwork repository; this publishes that
     * authoritative result to the shared UI state without creating a second controller.
     */
    fun applySelections(selections: Map<ArtworkSlot, ArtistArtworkSelection>) {
        val profile = _state.value.profile ?: return
        val verified = selections.filterValues {
            it.status == ArtworkLoadStatus.READY &&
                it.confirmedMbid.equals(profile.mbid, ignoreCase = true)
        }
        _state.value = _state.value.copy(selections = verified)
    }

    fun release() {
        ++requestEpoch
        requestJob?.cancel()
    }

    private suspend fun bootstrap(
        libraryId: String?,
        artistCredit: String,
        albumIds: List<String>,
        epoch: Int,
    ) {
        var cached: PersistedArtistProfile? = null
        if (libraryId != null) {
            cached = try {
                profileStore.load(libraryId, artistCredit, albumIds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (epoch != requestEpoch) return
                _state.value = ArtistEnrichmentState(
                    status = ArtistEnrichmentStatus.FAILED,
                    message = "No se pudieron leer los datos guardados del artista.",
                )
                return
            }
        }
        if (epoch != requestEpoch) return
        if (cached == null) {
            _state.value = ArtistEnrichmentState(status = ArtistEnrichmentStatus.IDLE)
            if (autoDiscoveryAllowed) discover(artistCredit)
            return
        }

        val profile = cached.profile
        val selections = loadVerifiedSelections(libraryId, artistCredit, albumIds, profile)
        val complete = !profile.biography.isNullOrBlank() &&
            selections.keys.containsAll(ArtworkSlot.entries)
        _state.value = ArtistEnrichmentState(
            status = ArtistEnrichmentStatus.VERIFIED,
            profile = profile,
            persisted = true,
            stale = cached.stale,
            selections = selections,
        )
        if (!complete || cached.stale) {
            refresh(artistCredit, libraryId, albumIds, force = cached.stale)
        }
    }

    private suspend fun loadVerifiedSelections(
        libraryId: String?,
        artistCredit: String,
        albumIds: List<String>,
        profile: ArtistExternalProfile,
    ): Map<ArtworkSlot, ArtistArtworkSelection> {
        val anchor = artistIdentityAnchor(libraryId, artistCredit, albumIds, profile)
            ?: return emptyMap()
        return try {
            artworkStore.load(anchor)
                .map { selection ->
                    try {
                        verifySelection(selection)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        selection.copy(status = ArtworkLoadStatus.CACHE_ERROR)
                    }
                }
                .filter { it.status == ArtworkLoadStatus.READY }
                .associateBy { it.slot }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun apply(
        result: ArtistEnrichmentResult,
        previous: ArtistExternalProfile?,
        epoch: Int,
    ) {
        if (epoch != requestEpoch) return
        val current = _state.value
        val profile = result.profile ?: previous
        val identityChanged = previous?.mbid != null && profile?.mbid != null &&
            !previous.mbid.equals(profile.mbid, ignoreCase = true)
        val status = if (profile == null) ArtistEnrichmentStatus.FAILED
        else ArtistEnrichmentStatus.VERIFIED
        _state.value = ArtistEnrichmentState(
            status = status,
            profile = profile,
            persisted = result.persisted || current.persisted,
            stale = false,
            refreshing = false,
            refreshFailed = previous != null && profile?.mbid == previous.mbid &&
                result.phase == ArtistEnrichmentPhase.PARTIAL,
            message = result.message,
            pendingPortraits = result.pendingPortraits,
            selections = mergeEnrichmentSelections(
                current = current.selections,
                incoming = result.selections,
                identityChanged = identityChanged,
            ),
        )
    }
}
