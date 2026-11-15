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
    val associationCandidates: List<ArtistAssociationCandidate> = emptyList(),
    val persisted: Boolean = false,
    /** True only when [profile] is the version confirmed by Room. */
    val profilePersisted: Boolean = false,
    /** MBID whose confirmed profile is actually present in Room, never inherited across identities. */
    val persistedMbid: String? = null,
    val persistenceStatus: ArtistPersistenceStatus = ArtistPersistenceStatus.NOT_ATTEMPTED,
    val persistenceError: String? = null,
    val associationGroupKey: String? = null,
    val needsConfirmation: Boolean = false,
    val stale: Boolean = false,
    val refreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val message: String? = null,
    val pendingPortraits: List<ArtistArtworkCandidate> = emptyList(),
    /** True only while an explicitly requested Openverse search is running. */
    val searchingPortraits: Boolean = false,
    val selections: Map<ArtworkSlot, ArtistArtworkSelection> = emptyMap(),
    /** Last artwork receipt for this identity; null until a provider run finishes. */
    val artworkReport: org.michimusic.mobile.artist.artwork.ArtworkResolutionReport? = null,
    val unboundAlbumIds: List<String> = emptyList(),
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
    private var portraitJob: Job? = null
    /**
     * Identity-scoped request epoch. A response from a previous artist, library or
     * explicit action is discarded instead of overwriting the current screen.
     */
    private var requestEpoch = 0
    /**
     * Portrait work has its own epoch: an image response must never be published after
     * the artist, library, association or MBID that requested it changed.
     */
    private var portraitEpoch = 0L
    private var portraitReceipt: ArtistPortraitSearchReceipt? = null

    fun bind(libraryId: String?, artistCredit: String, albumIds: List<String>) {
        val key = bindKeyFor(libraryId, artistCredit, albumIds)
        if (key == bindKey) return
        bindKey = key
        requestJob?.cancel()
        invalidatePortraitSearch()
        val epoch = ++requestEpoch
        _state.value = ArtistEnrichmentState()
        requestJob = scope.launch {
            bootstrap(libraryId, artistCredit, albumIds, epoch)
        }
    }

    /** Runs name discovery only after an explicit user action. */
    fun discover(artistCredit: String) {
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

    /** Persists one existing historical association; it never searches or confirms by name. */
    fun resolveAssociation(
        artistCredit: String,
        libraryId: String?,
        albumIds: List<String>,
        associationGroupKey: String,
        mbid: String,
    ) {
        val resolutionStore = profileStore as? ArtistAssociationResolutionStore ?: return
        val scopedLibrary = libraryId ?: return
        requestJob?.cancel()
        val epoch = ++requestEpoch
        _state.value = _state.value.copy(refreshing = true, message = null)
        requestJob = scope.launch {
            try {
                resolutionStore.resolveAssociation(
                    scopedLibrary, artistCredit, albumIds, associationGroupKey, mbid,
                )
                if (epoch != requestEpoch) return@launch
                bootstrap(scopedLibrary, artistCredit, albumIds, epoch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (epoch != requestEpoch) return@launch
                _state.value = _state.value.copy(
                    refreshing = false,
                    message = error.message ?: "La asociación elegida ya no está disponible.",
                )
            }
        }
    }

    /** User-only recovery of a legacy album binding that predates source proof.
     * The Room store checks exact ownership again; the UI never infers ownership.
     */
    fun reconfirmVisibleAlbum(
        artistCredit: String, libraryId: String?, albumIds: List<String>, albumId: String,
    ) {
        val bindingStore = profileStore as? ArtistMembershipBindingStore ?: return
        val concreteLibrary = libraryId?.takeIf { it.isNotBlank() && it != "SCOPE_ALL" } ?: return
        val current = _state.value
        val associationId = current.associationGroupKey ?: return
        val mbid = current.persistedMbid ?: return
        if (!current.persisted || !current.profilePersisted ||
            current.profile?.mbid?.equals(mbid, ignoreCase = true) != true ||
            albumId.isBlank() || albumId !in albumIds || albumId in current.unboundAlbumIds
        ) return
        requestJob?.cancel()
        val epoch = ++requestEpoch
        _state.value = current.copy(refreshing = true, message = null)
        requestJob = scope.launch {
            try {
                bindingStore.reconfirmExistingAlbumBinding(
                    libraryId = concreteLibrary, credit = artistCredit,
                    albumId = albumId, associationId = associationId, expectedMbid = mbid,
                )
                if (epoch != requestEpoch) return@launch
                bootstrap(concreteLibrary, artistCredit, albumIds, epoch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (epoch != requestEpoch) return@launch
                _state.value = _state.value.copy(
                    refreshing = false,
                    message = error.message ?: "No fue posible reconfirmar el álbum.",
                )
            }
        }
    }

    /**
     * Explicitly makes the currently visible album-credit rows members of the already verified
     * local association. This is a local Room operation: it never searches or refreshes a
     * provider and it never changes the association's MBID.
     */
    fun bindVisibleAlbums(
        artistCredit: String,
        libraryId: String?,
        albumIds: List<String>,
        albumIdToBind: String? = null,
    ) {
        val bindingStore = profileStore as? ArtistMembershipBindingStore ?: return
        val scopedLibrary = libraryId ?: return
        val current = _state.value
        val associationId = current.associationGroupKey ?: return
        val mbid = current.profile?.mbid ?: return
        // Reject any implicit multi-album confirmation. The selected album must be
        // pending in the last authoritative Room read AND visible in this context.
        val chosenAlbum = albumIdToBind ?: current.unboundAlbumIds.singleOrNull() ?: return
        if (chosenAlbum !in current.unboundAlbumIds || chosenAlbum !in albumIds) return
        requestJob?.cancel()
        val epoch = ++requestEpoch
        _state.value = current.copy(refreshing = true, message = null)
        requestJob = scope.launch {
            try {
                bindingStore.bindUnboundAlbumsToAssociation(
                    libraryId = scopedLibrary,
                    credit = artistCredit,
                    visibleAlbumIds = listOf(chosenAlbum),
                    associationId = associationId,
                    expectedMbid = mbid,
                )
                if (epoch != requestEpoch) return@launch
                bootstrap(scopedLibrary, artistCredit, albumIds, epoch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (epoch != requestEpoch) return@launch
                _state.value = _state.value.copy(
                    refreshing = false,
                    message = error.message ?: "No fue posible vincular estos álbumes.",
                )
            }
        }
    }

    /**
     * K47-ART-02: explicit, consented Openverse discovery.
     *
     * Nothing calls this from composition, from [bind] or from [refresh]; a photo search
     * runs only after the reader confirms it. A failed search never erases portraits
     * that were already verified for this identity.
     */
    fun searchPortraits(
        artistCredit: String,
        libraryId: String?,
        albumIds: List<String>,
        query: String = artistCredit,
    ) {
        val current = _state.value
        val profile = current.profile ?: return
        if (current.searchingPortraits) return
        if (profile.mbid.isBlank()) {
            _state.value = current.copy(
                message = "La identidad del artista aún no está confirmada; no se consultó ningún proveedor.",
            )
            return
        }
        val receipt = ArtistPortraitSearchReceipt.of(
            libraryId = libraryId,
            artistCredit = artistCredit,
            albumIds = albumIds,
            associationGroupKey = current.associationGroupKey,
            mbid = profile.mbid,
            requestSequence = ++portraitEpoch,
        )
        portraitReceipt = receipt
        portraitJob?.cancel()
        _state.value = current.copy(searchingPortraits = true, message = null)
        portraitJob = scope.launch {
            val result = try {
                val items = orchestrator.searchManualPortraits(
                    artistCredit = artistCredit,
                    libraryId = libraryId,
                    albumIds = albumIds,
                    profile = profile,
                    associationGroupKey = current.associationGroupKey,
                    query = query,
                )
                if (items.isEmpty()) {
                    ArtistPortraitSearchResult.NoMatch
                } else {
                    ArtistPortraitSearchResult.Candidates(items)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ArtistPortraitSearchResult.Failed("PROVIDER_UNAVAILABLE")
            }
            // A late answer from a previous identity is dropped, not shown and not persisted.
            if (!isCurrentPortraitReceipt(receipt)) return@launch
            _state.value = _state.value.copy(
                searchingPortraits = false,
                pendingPortraits = when (result) {
                    is ArtistPortraitSearchResult.Candidates -> result.items
                    else -> _state.value.pendingPortraits
                },
                message = when (result) {
                    is ArtistPortraitSearchResult.Candidates ->
                        "Retratos encontrados: ${result.items.size}. Revisa identidad y licencia antes de elegir."
                    ArtistPortraitSearchResult.NoMatch ->
                        "La búsqueda manual no devolvió retratos utilizables."
                    ArtistPortraitSearchResult.IdentityUnverified ->
                        "La identidad del artista aún no está confirmada; no se consultó ningún proveedor."
                    is ArtistPortraitSearchResult.Failed ->
                        "No fue posible consultar Openverse. No se modificó ninguna foto guardada."
                },
            )
        }
    }

    /** True only while [receipt] still belongs to the identity and action in progress. */
    internal fun isCurrentPortraitReceipt(receipt: ArtistPortraitSearchReceipt): Boolean =
        receipt.requestSequence == portraitEpoch && receipt == portraitReceipt

    /**
     * Drops any in-flight portrait request. Called whenever the identity anchor changes,
     * the enrichment is cleared or the owning screen releases the controller.
     */
    internal fun invalidatePortraitSearch() {
        ++portraitEpoch
        portraitReceipt = null
        portraitJob?.cancel()
        portraitJob = null
        if (_state.value.searchingPortraits) {
            _state.value = _state.value.copy(searchingPortraits = false, pendingPortraits = emptyList())
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
                        associationGroupKey = current.associationGroupKey,
                    )
                } else {
                    orchestrator.resolveArtwork(
                        artistCredit = artistCredit,
                        libraryId = libraryId,
                        albumIds = albumIds,
                        profile = profile,
                        associationGroupKey = current.associationGroupKey,
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
     * Removes the association and cancels in-flight work. A later provider search still
     * requires a new explicit user action; no bind starts discovery by itself.
     */
    fun clear(artistCredit: String, libraryId: String?, albumIds: List<String>) {
        requestJob?.cancel()
        invalidatePortraitSearch()
        ++requestEpoch
        val previous = _state.value
        val groupKey = previous.associationGroupKey
        val anchor = previous.profile?.let {
            artistIdentityAnchor(libraryId, artistCredit, albumIds, it, groupKey)
        }
        _state.value = ArtistEnrichmentState(
            status = ArtistEnrichmentStatus.IDLE,
            message = "Enriquecimiento borrado.",
        )
        requestJob = scope.launch {
            var profileCleared = false
            var failure = false
            if (libraryId != null) {
                try {
                    profileStore.clear(libraryId, artistCredit, albumIds, groupKey)
                    profileCleared = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failure = true
                }
            }
            if (anchor != null) {
                try {
                    artworkStore.clear(anchor)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failure = true
                }
            }
            if (failure) {
                _state.value = if (profileCleared) {
                    // The identity row is gone, so showing the previous profile again would
                    // be a lie. Keep the coherent empty state and disclose the file cleanup
                    // failure; an explicit repair can retry the unreferenced files.
                    ArtistEnrichmentState(
                        status = ArtistEnrichmentStatus.IDLE,
                        message = "Se borró la asociación, pero no todos los archivos de imagen.",
                    )
                } else {
                    previous.copy(message = "No fue posible borrar todos los datos guardados.")
                }
            }
        }
    }

    private fun bindKeyFor(libraryId: String?, artistCredit: String, albumIds: List<String>): String =
        listOf(
            libraryId.orEmpty(),
            artistCredit,
            albumIds.toSortedSet().joinToString("\u0000"),
        ).joinToString("\u0001")

    /**
     * A manual choice is persisted by the artwork repository; this publishes that
     * authoritative result to the shared UI state without creating a second controller.
     *
     * The full bind context is required: two libraries can legitimately contain the same
     * artist and MBID. A late response from library A must never mutate library B.
     */
    fun applySelections(
        libraryId: String?,
        artistCredit: String,
        albumIds: List<String>,
        mbid: String,
        selections: Map<ArtworkSlot, ArtistArtworkSelection>,
    ) {
        if (bindKeyFor(libraryId, artistCredit, albumIds) != bindKey) return
        val profile = _state.value.profile ?: return
        if (!profile.mbid.equals(mbid, ignoreCase = true)) return
        val verified = selections.filterValues {
            it.status == ArtworkLoadStatus.READY &&
                it.confirmedMbid.equals(profile.mbid, ignoreCase = true)
        }
        _state.value = _state.value.copy(selections = verified)
    }

    fun release() {
        ++requestEpoch
        requestJob?.cancel()
        invalidatePortraitSearch()
    }

    private suspend fun bootstrap(
        libraryId: String?,
        artistCredit: String,
        albumIds: List<String>,
        epoch: Int,
    ) {
        if (albumIds.isEmpty()) {
            ArtistEnrichmentDiagnostics.event("AE_CONTEXT_INVALID", "ALBUMS_LOADING")
            _state.value = ArtistEnrichmentState(
                status = ArtistEnrichmentStatus.IDLE,
                message = "Esperando la biblioteca local para recuperar este artista.",
            )
            return
        }
        var cached: PersistedArtistProfile? = null
        if (libraryId != null) {
            ArtistEnrichmentDiagnostics.event("AE_CONTEXT_READY")
            cached = try {
                profileStore.load(libraryId, artistCredit, albumIds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ArtistAssociationAmbiguousException) {
                if (epoch != requestEpoch) return
                ArtistEnrichmentDiagnostics.event("AE_ASSOCIATION_AMBIGUOUS")
                val choices = try {
                    (profileStore as? ArtistAssociationResolutionStore)
                        ?.ambiguousAssociations(libraryId, artistCredit, albumIds)
                        .orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                _state.value = ArtistEnrichmentState(
                    status = if (choices.isEmpty()) ArtistEnrichmentStatus.FAILED
                    else ArtistEnrichmentStatus.CHOOSING,
                    associationCandidates = choices,
                    message = if (choices.isEmpty()) {
                        "Hay asociaciones incompatibles, pero no fue posible recuperar alternativas válidas."
                    } else {
                        "Hay asociaciones históricas incompatibles; elige explícitamente la correcta."
                    },
                )
                return
            } catch (_: Exception) {
                if (epoch != requestEpoch) return
                ArtistEnrichmentDiagnostics.event("AE_LOAD_MISS", "STORE_ERROR")
                _state.value = ArtistEnrichmentState(
                    status = ArtistEnrichmentStatus.FAILED,
                    message = "No se pudieron leer los datos guardados del artista.",
                )
                return
            }
            ArtistEnrichmentDiagnostics.event(
                if (cached == null) "AE_LOAD_MISS" else "AE_LOAD_HIT",
            )
        } else {
            ArtistEnrichmentDiagnostics.event("AE_CONTEXT_INVALID", "NO_IDENTITY_LIBRARY")
        }
        if (epoch != requestEpoch) return
        if (cached == null) {
            if (libraryId == null) {
                ArtistEnrichmentDiagnostics.event("AE_CONTEXT_INVALID", "NO_LOCAL_LIBRARY")
                _state.value = ArtistEnrichmentState(
                    status = ArtistEnrichmentStatus.IDLE,
                    message = "Selecciona una biblioteca local para enriquecer este artista.",
                )
                return
            }
            _state.value = ArtistEnrichmentState(status = ArtistEnrichmentStatus.IDLE)
            return
        }

        val profile = cached.profile
        val selections = loadVerifiedSelections(
            libraryId, artistCredit, albumIds, profile, cached.associationGroupKey,
        )
        val complete = !profile.biography.isNullOrBlank() &&
            selections.keys.containsAll(ArtworkSlot.entries)
        _state.value = ArtistEnrichmentState(
            status = ArtistEnrichmentStatus.VERIFIED,
            profile = profile,
            persisted = true,
            persistedMbid = profile.mbid,
            persistenceStatus = if (cached.profilePersisted) {
                ArtistPersistenceStatus.PROFILE_PERSISTED
            } else {
                ArtistPersistenceStatus.IDENTITY_PERSISTED
            },
            profilePersisted = cached.profilePersisted,
            persistenceError = if (cached.profilePersisted) null else {
                "La actualización de datos no quedó guardada en este dispositivo."
            },
            associationGroupKey = cached.associationGroupKey,
            needsConfirmation = cached.needsConfirmation,
            unboundAlbumIds = cached.unboundAlbumIds,
            stale = cached.stale,
            selections = selections,
            message = when {
                cached.needsConfirmation ->
                    "Asociación recuperada de un grupo anterior; confirma si corresponde."
                !complete ->
                    "Datos guardados recuperados. Actualiza solo si lo necesitas."
                else -> null
            },
        )
        // Startup is a local hydration path. A stale biography or a missing optional
        // image is not permission to query providers: the saved MBID, profile and
        // verified files are already the durable truth until the user asks to update.
    }

    private suspend fun loadVerifiedSelections(
        libraryId: String?,
        artistCredit: String,
        albumIds: List<String>,
        profile: ArtistExternalProfile,
        associationGroupKey: String?,
    ): Map<ArtworkSlot, ArtistArtworkSelection> {
        val anchor = artistIdentityAnchor(
            libraryId, artistCredit, albumIds, profile, associationGroupKey,
        ) ?: return emptyMap()
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
        val samePersistedIdentity = result.persistenceStatus == ArtistPersistenceStatus.NOT_ATTEMPTED &&
            current.persisted &&
            current.persistedMbid != null &&
            profile?.mbid != null &&
            current.persistedMbid.equals(profile.mbid, ignoreCase = true)
        val persisted = result.identityPersisted || samePersistedIdentity
        val profilePersisted = when {
            result.profilePersisted -> true
            samePersistedIdentity -> current.profilePersisted
            result.persistenceStatus == ArtistPersistenceStatus.IDENTITY_PERSISTED &&
                previous == profile -> current.profilePersisted
            else -> false
        }
        val status = if (profile == null) ArtistEnrichmentStatus.FAILED
        else ArtistEnrichmentStatus.VERIFIED
        val refreshFailed = previous != null && profile?.mbid == previous.mbid &&
            result.phase == ArtistEnrichmentPhase.PARTIAL
        _state.value = ArtistEnrichmentState(
            status = status,
            profile = profile,
            persisted = persisted,
            profilePersisted = profilePersisted,
            persistedMbid = profile?.mbid?.takeIf { persisted },
            persistenceStatus = if (samePersistedIdentity) {
                current.persistenceStatus
            } else {
                result.persistenceStatus
            },
            persistenceError = when {
                result.persistenceStatus == ArtistPersistenceStatus.IDENTITY_PERSISTED &&
                    !result.profilePersisted ->
                    "La actualización de datos no quedó guardada en este dispositivo."
                result.persistenceError != null -> result.persistenceError
                result.persistenceStatus == ArtistPersistenceStatus.FAILED ->
                    "El perfil se muestra, pero no quedó guardado en este dispositivo."
                else -> null
            },
            associationGroupKey = result.associationGroupKey ?: current.associationGroupKey,
            // Profile refresh cannot prove any missing album-credit binding.
            // Only a membership operation or a change of association may clear this flag.
            needsConfirmation = if (
                identityChanged || current.associationGroupKey == null ||
                (result.associationGroupKey != null && result.associationGroupKey != current.associationGroupKey)
            ) false else current.needsConfirmation,
            unboundAlbumIds = if (
                identityChanged || current.associationGroupKey == null ||
                (result.associationGroupKey != null && result.associationGroupKey != current.associationGroupKey)
            ) emptyList() else current.unboundAlbumIds,
            stale = if (result.profilePersisted) false else current.stale,
            refreshing = false,
            refreshFailed = refreshFailed,
            message = result.message,
            pendingPortraits = result.pendingPortraits,
            selections = mergeEnrichmentSelections(
                current = current.selections,
                incoming = result.selections,
                identityChanged = identityChanged,
            ),
            artworkReport = if (identityChanged) result.artwork
                else result.artwork ?: current.artworkReport,
        )
    }
}
