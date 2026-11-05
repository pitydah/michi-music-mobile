package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * One owner for provider federation, ranking, download and persistence. No UI surface
 * calls a provider or writes an asset directly.
 */
class ArtistArtworkCoordinator internal constructor(
    private val wikidata: WikidataArtworkLookup,
    private val commons: CommonsImageInfo,
    private val openverse: OpenverseArtworkProvider,
    private val http: ArtworkHttp,
    private val downloader: ArtworkDownloader,
    private val store: ArtistArtworkStore,
    private val verifySelection: suspend (ArtistArtworkSelection) -> ArtistArtworkSelection = { it },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val automaticProviders: List<ArtistArtworkProvider> = listOf(
        CommonsP18Provider(http, wikidata, commons),
        CommonsP180Provider(http, wikidata, commons),
        WikipediaPageImageProvider(http, wikidata, commons),
        DisabledArtworkProvider(ArtworkSource.THEAUDIODB, "THEAUDIODB_ENABLED=false"),
        DisabledArtworkProvider(ArtworkSource.FANART_TV, "FANART_ENABLED=false"),
    )

    suspend fun resolve(anchor: ArtistIdentityAnchor, forceRefresh: Boolean = false): ArtworkResolutionReport {
        val lock = locks.computeIfAbsent(lockKey(anchor)) { Mutex() }
        return lock.withLock { resolveLocked(anchor, forceRefresh) }
    }

    private suspend fun resolveLocked(anchor: ArtistIdentityAnchor, forceRefresh: Boolean): ArtworkResolutionReport {
        val existing = runCatching { store.load(anchor) }.getOrElse { error ->
            return reportFailure(anchor, ArtworkLoadStatus.CACHE_ERROR, "No se pudo leer la caché de artwork: ${error.message}")
        }.associateBy { it.slot }
        if (!forceRefresh && existing.values.any { it.status == ArtworkLoadStatus.READY }) {
            return reportFromCache(anchor, existing)
        }

        val outcomes = mutableListOf<ArtworkProviderOutcome>()
        val candidates = mutableListOf<ArtistArtworkCandidate>()
        for (provider in automaticProviders) {
            if (provider is DisabledArtworkProvider) {
                outcomes += ArtworkProviderOutcome(provider.source, ArtworkProviderStatus.DISABLED, "Proveedor desactivado")
                continue
            }
            if (candidates.any(::coversBothSlots)) {
                outcomes += ArtworkProviderOutcome(provider.source, ArtworkProviderStatus.SKIPPED, "EARLY_CUT")
                continue
            }
            val result = try {
                provider.findCandidates(anchor, ArtworkSlot.ARTIST_THUMB)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure(error)
            }
            when (result) {
                is ProviderArtworkResult.Available -> {
                    val valid = result.items.filter { allowedArtworkCandidate(it, anchor) }
                    candidates += valid
                    outcomes += ArtworkProviderOutcome(
                        provider.source,
                        if (valid.isEmpty()) ArtworkProviderStatus.NO_MATCH else ArtworkProviderStatus.AVAILABLE,
                        if (valid.isEmpty()) "Sin candidato que cumpla identidad y licencia" else null,
                        valid.size,
                    )
                }
                is ProviderArtworkResult.NoMatch ->
                    outcomes += ArtworkProviderOutcome(provider.source, ArtworkProviderStatus.NO_MATCH, "Sin coincidencias")
                is ProviderArtworkResult.Failure ->
                    outcomes += ArtworkProviderOutcome(provider.source, ArtworkProviderStatus.FAILURE, result.type, 0)
                is ProviderArtworkResult.Disabled ->
                    outcomes += ArtworkProviderOutcome(provider.source, ArtworkProviderStatus.DISABLED, result.reason)
            }
        }

        val distinct = candidates.distinctBy { it.stableAssetId }
        val chosenThumb = distinct.sortedWith(artworkRankingComparator(ArtworkSlot.ARTIST_THUMB)).firstOrNull()
        val chosenHero = distinct.sortedWith(artworkRankingComparator(ArtworkSlot.ARTIST_HERO)).firstOrNull()
        val heroCandidate = when {
            chosenHero != null && chosenHero.stableAssetId != chosenThumb?.stableAssetId -> chosenHero
            chosenThumb != null && canReuseThumbAsHero(chosenThumb) -> chosenThumb
            chosenHero != null && canReuseThumbAsHero(chosenHero) -> chosenHero
            else -> null
        }
        val downloads = mutableMapOf<String, Result<DownloadedArtwork>>()
        suspend fun download(candidate: ArtistArtworkCandidate): Result<DownloadedArtwork> {
            downloads[candidate.stableAssetId]?.let { return it }
            val result = try {
                Result.success(downloader.download(candidate))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            downloads[candidate.stableAssetId] = result
            return result
        }

        val saved = existing.toMutableMap()
        var cacheError: String? = null
        if (chosenThumb != null) {
            val downloaded = download(chosenThumb)
            if (downloaded.isSuccess) {
                val stored = try {
                    store.save(anchor, chosenThumb, ArtworkSlot.ARTIST_THUMB, downloaded.getOrThrow(),
                        ArtworkFocus(0.5f, 0.5f), manuallyChosen = false)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    cacheError = error.message
                    null
                }
                if (stored != null) saved[ArtworkSlot.ARTIST_THUMB] = stored
            } else {
                cacheError = downloaded.exceptionOrNull()?.message
            }
        }
        if (heroCandidate != null) {
            val downloaded = download(heroCandidate)
            if (downloaded.isSuccess) {
                val stored = try {
                    store.save(anchor, heroCandidate, ArtworkSlot.ARTIST_HERO, downloaded.getOrThrow(),
                        ArtworkFocus(0.5f, 0.45f), manuallyChosen = false)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    cacheError = error.message
                    null
                }
                if (stored != null) saved[ArtworkSlot.ARTIST_HERO] = stored
            } else {
                cacheError = cacheError ?: downloaded.exceptionOrNull()?.message
            }
        }

        val ready = saved.filterValues { it.status == ArtworkLoadStatus.READY }
        val status = when {
            ready.size == 2 -> ArtworkLoadStatus.READY
            ready.isNotEmpty() -> ArtworkLoadStatus.PARTIAL
            cacheError != null -> ArtworkLoadStatus.CACHE_ERROR
            outcomes.any { it.status == ArtworkProviderStatus.FAILURE } &&
                outcomes.none { it.status == ArtworkProviderStatus.AVAILABLE } -> {
                if (outcomes.any { it.reason in REJECTION_REASONS }) ArtworkLoadStatus.REJECTED
                else ArtworkLoadStatus.PROVIDER_ERROR
            }
            else -> ArtworkLoadStatus.NO_IMAGE
        }
        return ArtworkResolutionReport(
            status = status,
            outcomes = outcomes,
            selectedAssets = ArtworkSlot.entries.associateWith { saved[it]?.assetId },
            selections = saved,
            message = cacheError ?: outcomes.firstOrNull { it.status == ArtworkProviderStatus.FAILURE }?.reason,
        )
    }

    suspend fun searchManual(anchor: ArtistIdentityAnchor, query: String): ProviderArtworkResult =
        openverse.search(anchor, query)

    /**
     * Explicitly chosen Openverse candidate: origin HTML must confirm the exact licence
     * before bytes are downloaded. It never becomes automatic and never replaces a
     * verified slot without this direct user action.
     */
    suspend fun chooseManual(
        anchor: ArtistIdentityAnchor,
        candidate: ArtistArtworkCandidate,
        slot: ArtworkSlot,
    ): ArtworkResolutionReport {
        if (!candidate.isManualEligible()) {
            return reportFailure(anchor, ArtworkLoadStatus.REJECTED, "Candidato manual no admitido")
        }
        if (candidate.confirmedMbid != anchor.confirmedMbid) {
            return reportFailure(anchor, ArtworkLoadStatus.REJECTED, "Identidad no coincidente")
        }
        try {
            val html = http.readText(candidate.canonicalSourcePage, setOf("www.flickr.com"))
            if (!originPageConfirmsLicense(candidate.canonicalSourcePage, candidate.licenseUrl, html)) {
                return reportFailure(anchor, ArtworkLoadStatus.REJECTED, "El origen no confirma la licencia")
            }
            val downloaded = downloader.download(candidate)
            val verified = candidate.copy(
                originalWidth = downloaded.width,
                originalHeight = downloaded.height,
                mimeType = downloaded.mimeType,
                evidence = ArtworkEvidence.USER_SELECTED,
                rights = ArtworkRights.VERIFIED_ALLOWED,
                localRelativePath = downloaded.relativePath,
                downloadedSha256 = downloaded.sha256,
            )
            if (!allowedArtworkCandidate(verified, anchor)) {
                return reportFailure(anchor, ArtworkLoadStatus.REJECTED, "El candidato no cumple las reglas de artwork")
            }
            val selection = store.save(
                anchor, verified, slot, downloaded,
                ArtworkFocus(0.5f, 0.5f), manuallyChosen = true,
            )
            val selections = store.load(anchor).associateBy { it.slot }
            return ArtworkResolutionReport(
                status = if (selections.values.any { it.status == ArtworkLoadStatus.READY }) {
                    ArtworkLoadStatus.READY
                } else {
                    ArtworkLoadStatus.PARTIAL
                },
                outcomes = listOf(ArtworkProviderOutcome(ArtworkSource.OPENVERSE,
                    ArtworkProviderStatus.AVAILABLE, "USER_SELECTED", 1)),
                selectedAssets = ArtworkSlot.entries.associateWith { selections[it]?.assetId },
                selections = selections,
                message = null,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return reportFailure(anchor, ArtworkLoadStatus.CACHE_ERROR, error.message)
        }
    }

    suspend fun updateFocus(anchor: ArtistIdentityAnchor, slot: ArtworkSlot, focus: ArtworkFocus): Boolean =
        store.updateFocus(anchor, slot, focus)

    private suspend fun reportFromCache(
        anchor: ArtistIdentityAnchor,
        selections: Map<ArtworkSlot, ArtistArtworkSelection>,
    ): ArtworkResolutionReport {
        val verified = selections.mapValues { (_, selection) -> verifySelection(selection) }
        val status = when {
            verified.values.count { it.status == ArtworkLoadStatus.READY } == 2 -> ArtworkLoadStatus.READY
            verified.values.any { it.status == ArtworkLoadStatus.READY } -> ArtworkLoadStatus.PARTIAL
            else -> ArtworkLoadStatus.CACHE_ERROR
        }
        return ArtworkResolutionReport(
            status = status,
            outcomes = emptyList(),
            selectedAssets = ArtworkSlot.entries.associateWith { verified[it]?.assetId },
            selections = verified,
            fromCache = true,
        )
    }

    private suspend fun reportFailure(
        anchor: ArtistIdentityAnchor,
        status: ArtworkLoadStatus,
        message: String?,
    ): ArtworkResolutionReport {
        val selections = runCatching { store.load(anchor).associateBy { it.slot } }.getOrDefault(emptyMap())
        return ArtworkResolutionReport(
            status = status,
            outcomes = emptyList(),
            selectedAssets = ArtworkSlot.entries.associateWith { selections[it]?.assetId },
            selections = selections,
            message = message,
        )
    }

    private fun coversBothSlots(candidate: ArtistArtworkCandidate): Boolean =
        canReuseThumbAsHero(candidate) &&
            (candidate.originalWidth ?: 0) >= 1_000 &&
            (candidate.originalHeight ?: 0) >= 700

    private fun lockKey(anchor: ArtistIdentityAnchor): String =
        "${anchor.libraryId}\u0000${anchor.creditKey}\u0000${anchor.albumGroupKey}\u0000${anchor.confirmedMbid}"

    companion object {
        private val REJECTION_REASONS = setOf("NO_ALLOWED_LICENSE", "BAD_ATTRIBUTION", "BAD_FILE_TITLE", "NO_IMAGE_URL")
    }
}
