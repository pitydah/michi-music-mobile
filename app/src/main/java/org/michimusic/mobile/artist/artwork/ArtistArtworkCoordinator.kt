package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToLong

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
    private data class LockRef(val mutex: Mutex, var holders: Int)

    /** Locks are reference-counted so a long artist list cannot leak one mutex per anchor. */
    private val locks = HashMap<String, LockRef>()
    private val automaticProviders: List<ArtistArtworkProvider> = listOf(
        CommonsP18Provider(http, wikidata, commons),
        CommonsP180Provider(http, wikidata, commons),
        WikipediaPageImageProvider(http, wikidata, commons),
        DisabledArtworkProvider(ArtworkSource.THEAUDIODB, "THEAUDIODB_ENABLED=false"),
        DisabledArtworkProvider(ArtworkSource.FANART_TV, "FANART_ENABLED=false"),
    )

    suspend fun resolve(anchor: ArtistIdentityAnchor, forceRefresh: Boolean = false): ArtworkResolutionReport =
        withAnchorLock(anchor) { resolveLocked(anchor, forceRefresh) }

    private suspend fun <T> withAnchorLock(anchor: ArtistIdentityAnchor, block: suspend () -> T): T {
        val key = lockKey(anchor)
        val ref = synchronized(locks) {
            locks.getOrPut(key) { LockRef(Mutex(), 0) }.also { it.holders++ }
        }
        try {
            return ref.mutex.withLock { block() }
        } finally {
            synchronized(locks) {
                ref.holders--
                if (ref.holders == 0) locks.remove(key, ref)
            }
        }
    }

    private suspend fun resolveLocked(anchor: ArtistIdentityAnchor, forceRefresh: Boolean): ArtworkResolutionReport {
        val existingList = runCatching { store.load(anchor) }.getOrElse { error ->
            return reportFailure(anchor, ArtworkLoadStatus.CACHE_ERROR,
                "No se pudo leer la caché de artwork: ${error.message}")
        }
        // A row with status READY only means the file existed when it was read. Verify
        // the bytes before trusting the slot; otherwise a corrupt thumb would suppress
        // recovery forever while a valid hero must still be preserved.
        val verified = existingList.map { selection ->
            if (selection.status != ArtworkLoadStatus.READY) selection
            else try {
                verifySelection(selection)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                selection.copy(status = ArtworkLoadStatus.CACHE_ERROR)
            }
        }
        val existing = verified
            .filter { it.status == ArtworkLoadStatus.READY }
            .associateBy { it.slot }
        val manualSlots = existing.filterValues { it.manuallyChosen }.keys
        val needThumb = ArtworkSlot.ARTIST_THUMB !in manualSlots &&
            (ArtworkSlot.ARTIST_THUMB !in existing || forceRefresh)
        val needHero = ArtworkSlot.ARTIST_HERO !in manualSlots &&
            (ArtworkSlot.ARTIST_HERO !in existing || forceRefresh)
        if (!needThumb && !needHero) return reportFromCache(anchor, existing)

        val outcomes = mutableListOf<ArtworkProviderOutcome>()
        val candidates = mutableListOf<ArtistArtworkCandidate>()
        for (provider in automaticProviders) {
            if (provider is DisabledArtworkProvider) {
                outcomes += ArtworkProviderOutcome(provider.source, ArtworkProviderStatus.DISABLED, "Proveedor desactivado")
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
        val chosenThumb = if (needThumb) {
            distinct.sortedWith(artworkRankingComparator(ArtworkSlot.ARTIST_THUMB)).firstOrNull()
        } else null
        val chosenHero = if (needHero) {
            distinct.sortedWith(heroComparator).firstOrNull()
        } else null
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
        suspend fun saveSlot(candidate: ArtistArtworkCandidate, slot: ArtworkSlot, focus: ArtworkFocus) {
            val downloaded = download(candidate)
            if (downloaded.isSuccess) {
                val stored = try {
                    store.save(anchor, candidate, slot, downloaded.getOrThrow(), focus, manuallyChosen = false)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    cacheError = error.message
                    null
                }
                if (stored != null) saved[slot] = stored
            } else {
                cacheError = cacheError ?: downloaded.exceptionOrNull()?.message
            }
        }
        if (chosenThumb != null) saveSlot(chosenThumb, ArtworkSlot.ARTIST_THUMB, ArtworkFocus(0.5f, 0.5f))
        if (chosenHero != null) saveSlot(chosenHero, ArtworkSlot.ARTIST_HERO, ArtworkFocus(0.5f, 0.45f))
        if (chosenThumb != null || chosenHero != null) {
            runCatching { store.pruneCache() }
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
    ): ArtworkResolutionReport = withAnchorLock(anchor) {
        if (!candidate.isManualEligible()) {
            return@withAnchorLock reportFailure(anchor, ArtworkLoadStatus.REJECTED, "Candidato manual no admitido")
        }
        if (candidate.confirmedMbid != anchor.confirmedMbid) {
            return@withAnchorLock reportFailure(anchor, ArtworkLoadStatus.REJECTED, "Identidad no coincidente")
        }
        try {
            val html = http.readText(candidate.canonicalSourcePage, setOf("www.flickr.com"))
            if (!originPageConfirmsLicense(candidate.canonicalSourcePage, candidate.licenseUrl, html)) {
                return@withAnchorLock reportFailure(anchor, ArtworkLoadStatus.REJECTED, "El origen no confirma la licencia")
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
                return@withAnchorLock reportFailure(anchor, ArtworkLoadStatus.REJECTED, "El candidato no cumple las reglas de artwork")
            }
            val selection = store.save(
                anchor, verified, slot, downloaded,
                ArtworkFocus(0.5f, 0.5f), manuallyChosen = true,
            )
            val selections = store.load(anchor).associateBy { it.slot }
            return@withAnchorLock ArtworkResolutionReport(
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
            return@withAnchorLock reportFailure(anchor, ArtworkLoadStatus.CACHE_ERROR, error.message)
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

    /**
     * Hero ranking prioritises a clearly better landscape crop over provider order.
     * P18/P180 still win ties, so identity-specific sources remain preferred when
     * their pixels are comparable.
     */
    private val heroComparator = Comparator<ArtistArtworkCandidate> { a, b ->
        val scoreA = heroScore(a)
        val scoreB = heroScore(b)
        when {
            scoreA != scoreB -> scoreB.compareTo(scoreA)
            a.source != b.source -> a.source.ordinal.compareTo(b.source.ordinal)
            else -> a.stableAssetId.compareTo(b.stableAssetId)
        }
    }

    private fun heroScore(candidate: ArtistArtworkCandidate): Long {
        val width = candidate.originalWidth?.toLong() ?: 0L
        val height = candidate.originalHeight?.toLong() ?: 0L
        val pixels = width * height
        return if (width >= height) pixels + pixels / 4 else pixels
    }

    private fun lockKey(anchor: ArtistIdentityAnchor): String =
        "${anchor.libraryId}\u0000${anchor.creditKey}\u0000${anchor.albumGroupKey}\u0000${anchor.confirmedMbid}"

    companion object {
        private val REJECTION_REASONS = setOf("NO_ALLOWED_LICENSE", "BAD_ATTRIBUTION", "BAD_FILE_TITLE", "NO_IMAGE_URL")
    }
}
