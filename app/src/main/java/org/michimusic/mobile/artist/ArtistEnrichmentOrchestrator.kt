package org.michimusic.mobile.artist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.michimusic.mobile.artist.artwork.ArtistArtworkCoordinator
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkResolutionReport
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ProviderArtworkResult
import org.michimusic.mobile.artist.artwork.artistIdentityAnchor

/** One user-visible phase for identity, biography and artwork, in that order. */
enum class ArtistEnrichmentPhase {
    VERIFYING,
    BIOGRAPHY,
    PHOTOS,
    READY,
    PARTIAL,
    NO_PHOTOS,
    ERROR,
}

/** Provider success is not persistence success. */
enum class ArtistPersistenceStatus {
    NOT_ATTEMPTED,
    /** The confirmed MBID and local association are durable; displayed provider facts may not be. */
    IDENTITY_PERSISTED,
    /** The displayed profile is the profile whose Room write completed. */
    PROFILE_PERSISTED,
    FAILED,
    CONTEXT_UNAVAILABLE,
}

data class ArtistEnrichmentResult(
    val phase: ArtistEnrichmentPhase,
    val profile: ArtistExternalProfile? = null,
    val artwork: ArtworkResolutionReport? = null,
    val pendingPortraits: List<org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate> = emptyList(),
    val candidates: List<ArtistLookupCandidate> = emptyList(),
    val persisted: Boolean = false,
    val persistenceStatus: ArtistPersistenceStatus = ArtistPersistenceStatus.NOT_ATTEMPTED,
    val persistenceError: String? = null,
    val associationGroupKey: String? = null,
    val stale: Boolean = false,
    val message: String? = null,
) {
    val identityPersisted: Boolean
        get() = persistenceStatus == ArtistPersistenceStatus.IDENTITY_PERSISTED ||
            persistenceStatus == ArtistPersistenceStatus.PROFILE_PERSISTED

    val profilePersisted: Boolean
        get() = persistenceStatus == ArtistPersistenceStatus.PROFILE_PERSISTED

    val selections: Map<ArtworkSlot, ArtistArtworkSelection>
        get() = artwork?.selections.orEmpty()
}

/**
 * Owns the automatic enrichment journey: identity -> biography -> both artwork slots.
 * It composes the existing gateway and artwork coordinator; it never invents a provider
 * or a second persistence path. Calls for the same artist are serialized.
 */
class ArtistEnrichmentOrchestrator(
    private val gateway: ArtistEnrichmentGateway = ArtistEnrichmentService.repository,
    private val artwork: ArtistArtworkCoordinator,
    private val store: ArtistEnrichmentSelectionStore,
) {
    private data class LockRef(val mutex: Mutex, var holders: Int)

    private val locks = HashMap<String, LockRef>()

    suspend fun discover(artistCredit: String): List<ArtistLookupCandidate> =
        gateway.findCandidates(artistCredit)

    /**
     * Resolves one confirmed MBID. A cached profile is returned even when the refresh
     * fails, so a provider outage never hides previously verified data.
     */
    suspend fun enrich(
        artistCredit: String,
        libraryId: String?,
        albumIds: List<String>,
        mbid: String,
        cached: ArtistExternalProfile? = null,
        forceRefresh: Boolean = false,
        associationGroupKey: String? = null,
    ): ArtistEnrichmentResult {
        val canonical = canonicalArtistMbid(mbid) ?: return ArtistEnrichmentResult(
            phase = ArtistEnrichmentPhase.ERROR,
            profile = cached,
            message = "MBID no válido",
        )
        if (!canonical.equals(mbid, ignoreCase = true)) {
            return ArtistEnrichmentResult(
                phase = ArtistEnrichmentPhase.ERROR,
                profile = cached,
                message = "La identidad no coincide con el MBID confirmado",
            )
        }
        val key = listOf(libraryId.orEmpty(), artistCredit, albumIds.sorted().joinToString("\u0000"), canonical)
            .joinToString("\u0001")
        val ref = synchronized(locks) {
            locks.getOrPut(key) { LockRef(Mutex(), 0) }.also { it.holders++ }
        }
        try {
            return ref.mutex.withLock {
                enrichLocked(
                    artistCredit, libraryId, albumIds, canonical, cached, forceRefresh,
                    associationGroupKey,
                )
            }
        } finally {
            synchronized(locks) {
                ref.holders--
                if (ref.holders == 0) locks.remove(key, ref)
            }
        }
    }

    private suspend fun enrichLocked(
        artistCredit: String,
        libraryId: String?,
        albumIds: List<String>,
        mbid: String,
        cached: ArtistExternalProfile?,
        forceRefresh: Boolean,
        associationGroupKey: String?,
    ): ArtistEnrichmentResult {
        if (libraryId == null || scopedArtistCreditKey(libraryId, artistCredit, albumIds) == null) {
            return ArtistEnrichmentResult(
                phase = ArtistEnrichmentPhase.ERROR,
                profile = cached,
                persistenceStatus = ArtistPersistenceStatus.CONTEXT_UNAVAILABLE,
                persistenceError = "Contexto local incompleto; la identidad no quedó guardada.",
                message = "Selecciona una biblioteca local antes de confirmar la identidad.",
            )
        }
        val persistedIdentity = try {
            ArtistEnrichmentDiagnostics.event("AE_IDENTITY_SAVE_ATTEMPT")
            store.saveIdentityFirst(
                libraryId, artistCredit, albumIds, mbid, associationGroupKey,
            ).also {
                ArtistEnrichmentDiagnostics.event("AE_IDENTITY_SAVE_COMMITTED")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val detail = error.message ?: "No fue posible guardar la identidad."
            ArtistEnrichmentDiagnostics.event("AE_IDENTITY_SAVE_FAILED", detail)
            return ArtistEnrichmentResult(
                phase = ArtistEnrichmentPhase.ERROR,
                profile = cached,
                persistenceStatus = ArtistPersistenceStatus.FAILED,
                persistenceError = detail,
                message = "No fue posible guardar la identidad confirmada.",
            )
        }
        val effectiveAssociationGroupKey = persistedIdentity.associationGroupKey
            ?: associationGroupKey
        val identityProfile = cached?.takeIf { canonicalArtistMbid(it.mbid) == mbid }
            ?: persistedIdentity.profile
        val profile = try {
            val fetched = if (forceRefresh) gateway.refreshProfile(mbid) else gateway.confirmedProfile(mbid)
            if (canonicalArtistMbid(fetched.mbid) != mbid) {
                return ArtistEnrichmentResult(
                    phase = ArtistEnrichmentPhase.ERROR,
                    profile = identityProfile,
                    persisted = true,
                    persistenceStatus = ArtistPersistenceStatus.IDENTITY_PERSISTED,
                    associationGroupKey = effectiveAssociationGroupKey,
                    message = "El proveedor devolvió otra identidad",
                )
            }
            ArtistEnrichmentDiagnostics.event("AE_PROFILE_FETCHED")
            fetched
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Identity and artwork are independent stages. When a cached profile still
            // carries a verified QID, a MusicBrainz outage must not block Commons from
            // repairing a missing or corrupt slot. Only the profile refresh is reported
            // as pending; the stored identity is never discarded.
            if (identityProfile.wikidataQid != null
            ) {
                val repaired = finish(
                    artistCredit = artistCredit,
                    libraryId = libraryId,
                    albumIds = albumIds,
                    profile = identityProfile,
                    forceRefresh = true,
                    persistenceStatus = ArtistPersistenceStatus.IDENTITY_PERSISTED,
                    persistenceError = null,
                    associationGroupKey = effectiveAssociationGroupKey,
                )
                return repaired.copy(
                    phase = ArtistEnrichmentPhase.PARTIAL,
                    message = "No fue posible actualizar el perfil; se reintentó la artwork con la identidad guardada.",
                )
            }
            return ArtistEnrichmentResult(
                phase = ArtistEnrichmentPhase.PARTIAL,
                profile = identityProfile,
                persisted = true,
                persistenceStatus = ArtistPersistenceStatus.IDENTITY_PERSISTED,
                associationGroupKey = effectiveAssociationGroupKey,
                message = "No fue posible actualizar los datos externos",
            )
        }

        var persistenceStatus = ArtistPersistenceStatus.PROFILE_PERSISTED
        var persistenceError: String? = null
        ArtistEnrichmentDiagnostics.event("AE_SAVE_ATTEMPT")
        try {
            store.save(
                libraryId, artistCredit, albumIds, profile, effectiveAssociationGroupKey,
            )
            ArtistEnrichmentDiagnostics.event("AE_SAVE_COMMITTED")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The identity-first row remains authoritative even if optional profile
            // enrichment cannot be committed.
            persistenceError = error.message ?: "No fue posible guardar el perfil."
            persistenceStatus = ArtistPersistenceStatus.IDENTITY_PERSISTED
            ArtistEnrichmentDiagnostics.event("AE_PROFILE_SAVE_FAILED", persistenceError)
        }

        return finish(
            artistCredit, libraryId, albumIds, profile, forceRefresh,
            persistenceStatus, persistenceError, effectiveAssociationGroupKey,
        )
    }

    /**
     * Repairs artwork only. A complete verified profile must never be fetched again just
     * because one local slot is missing or corrupt.
     */
    suspend fun resolveArtwork(
        artistCredit: String,
        libraryId: String?,
        albumIds: List<String>,
        profile: ArtistExternalProfile,
        forceRefresh: Boolean = false,
        associationGroupKey: String? = null,
    ): ArtistEnrichmentResult = finish(
        artistCredit = artistCredit,
        libraryId = libraryId,
        albumIds = albumIds,
        profile = profile,
        forceRefresh = forceRefresh,
        persistenceStatus = ArtistPersistenceStatus.NOT_ATTEMPTED,
        persistenceError = null,
        associationGroupKey = associationGroupKey,
    )

    private suspend fun finish(
        artistCredit: String,
        libraryId: String?,
        albumIds: List<String>,
        profile: ArtistExternalProfile,
        forceRefresh: Boolean,
        persistenceStatus: ArtistPersistenceStatus,
        persistenceError: String?,
        associationGroupKey: String?,
    ): ArtistEnrichmentResult {
        val anchor = artistIdentityAnchor(
            libraryId, artistCredit, albumIds, profile, associationGroupKey,
        )
        // Without a verified Wikidata QID no first-party provider can establish that a
        // photo depicts this artist. Do not block the verified profile on artwork work
        // that cannot succeed; the legacy portrait remains a separate adapter.
        val report = if (anchor?.verifiedQid != null) {
            try {
                artwork.resolve(anchor, forceRefresh = forceRefresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else null

        // Openverse runs automatically only when a verified identity let the
        // first-party providers run and they still left a visual slot uncovered.
        // Name-only searches without a verified QID stay explicit, never automatic.
        var pending: List<org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate> = emptyList()
        if (anchor?.verifiedQid != null &&
            (report == null || !report.selections.keys.containsAll(ArtworkSlot.entries))
        ) {
            pending = try {
                when (val result = artwork.searchManual(anchor, artistCredit)) {
                    is ProviderArtworkResult.Available -> result.items
                    else -> emptyList()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }

        val hasBoth = report?.selections?.keys?.containsAll(ArtworkSlot.entries) == true
        val hasBio = !profile.biography.isNullOrBlank()
        val phase = when {
            hasBoth && hasBio -> ArtistEnrichmentPhase.READY
            !hasBoth && !hasBio -> ArtistEnrichmentPhase.NO_PHOTOS
            hasBio || hasBoth || report?.selections?.isNotEmpty() == true -> ArtistEnrichmentPhase.PARTIAL
            else -> ArtistEnrichmentPhase.NO_PHOTOS
        }
        val message = when {
            phase == ArtistEnrichmentPhase.READY -> "Ficha actualizada."
            phase == ArtistEnrichmentPhase.NO_PHOTOS -> "Sin fotografías verificables."
            report?.status == ArtworkLoadStatus.CACHE_ERROR ->
                "La caché de imágenes necesita reparación; se mantiene la ficha verificada."
            phase == ArtistEnrichmentPhase.PARTIAL && report?.selections?.isEmpty() != false ->
                "Ficha actualizada sin fotografías verificables."
            phase == ArtistEnrichmentPhase.PARTIAL -> "Ficha parcialmente disponible."
            report == null -> "La ficha se actualizó sin artwork."
            else -> null
        }
        return ArtistEnrichmentResult(
            phase = phase,
            profile = profile,
            artwork = report,
            pendingPortraits = pending,
            persisted = persistenceStatus == ArtistPersistenceStatus.IDENTITY_PERSISTED ||
                persistenceStatus == ArtistPersistenceStatus.PROFILE_PERSISTED,
            persistenceStatus = persistenceStatus,
            persistenceError = persistenceError,
            associationGroupKey = associationGroupKey,
            message = message,
        )
    }
}
