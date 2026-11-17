package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.michimusic.mobile.artist.ArtistEnrichmentDiagnostics

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
    private val keys: suspend () -> FanartTvProvider.Keys = { FanartTvProvider.Keys(null, null) },
) {
    private data class LockRef(val mutex: Mutex, var holders: Int)

    /** Locks are reference-counted so a long artist list cannot leak one mutex per anchor. */
    private val locks = HashMap<String, LockRef>()

    /** Manual discovery only: never part of [automaticProviders]. */
    private val theAudioDb = TheAudioDbProvider(http)

    /**
     * Manual-only, like TheAudioDB: fanart.tv publishes photographs and declares no
     * licence for them. It is also keyed by MusicBrainz id, so it can only answer once
     * the identity is confirmed.
     */
    private val fanartTv = FanartTvProvider(http, keys)
    private val automaticProviders: List<ArtistArtworkProvider> = listOf(
        CommonsP18Provider(http, wikidata, commons),
        CommonsP180Provider(http, wikidata, commons),
        WikipediaPageImageProvider(http, wikidata, commons),
        DisabledArtworkProvider(ArtworkSource.THEAUDIODB, "THEAUDIODB_ENABLED=false"),
        DisabledArtworkProvider(ArtworkSource.FANART_TV, "Sólo revisión manual: sin licencia declarada"),
    )

    suspend fun resolve(anchor: ArtistIdentityAnchor, forceRefresh: Boolean = false): ArtworkResolutionReport =
        withAnchorLock(anchor) { resolveLocked(anchor, forceRefresh,
            expectedAutoSource = null, expectedAutoEpoch = null, protectedSlots = emptySet()) }

    /** Only WorkManager may request this path. A late download must pass Room again. */
    suspend fun resolveAutomated(
        anchor: ArtistIdentityAnchor,
        expectedSourceId: String,
        expectedRootEpoch: Long? = null,
        protectedSlots: Set<ArtworkSlot> = emptySet(),
    ): ArtworkResolutionReport = withAnchorLock(anchor) {
        resolveLocked(anchor, forceRefresh = false, expectedAutoSource = expectedSourceId,
            expectedAutoEpoch = expectedRootEpoch, protectedSlots = protectedSlots)
    }

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

    private suspend fun resolveLocked(
        anchor: ArtistIdentityAnchor, forceRefresh: Boolean, expectedAutoSource: String?,
        expectedAutoEpoch: Long?, protectedSlots: Set<ArtworkSlot>,
    ): ArtworkResolutionReport {
        ArtistEnrichmentDiagnostics.event("AE_RECOVERY_STARTED")
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
        // A manually chosen image is owned by the user even when its bytes are corrupt.
        // Do not replace it silently during either automatic recovery or force refresh.
        val manualSlots = verified.asSequence()
            .filter { it.manuallyChosen }
            .map { it.slot }.toSet() + protectedSlots
        val needThumb = ArtworkSlot.ARTIST_THUMB !in manualSlots &&
            (ArtworkSlot.ARTIST_THUMB !in existing || forceRefresh)
        val needHero = ArtworkSlot.ARTIST_HERO !in manualSlots &&
            (ArtworkSlot.ARTIST_HERO !in existing || forceRefresh)
        if (!needThumb && !needHero) {
            ArtistEnrichmentDiagnostics.event("AE_RECOVERY_COMPLETED", "CACHE")
            return reportFromCache(anchor, existing)
        }

        val outcomes = mutableListOf<ArtworkProviderOutcome>()
        val distinct = LinkedHashMap<String, ArtistArtworkCandidate>()
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
        val attemptedBySlot = mutableMapOf<ArtworkSlot, MutableSet<String>>()
        // A new provider gets an independent 3-attempt budget, but every slot
        // retains one finite global cap to avoid unbounded downloads.
        val attemptedInStageBySlot = mutableMapOf<ArtworkSlot, MutableSet<String>>()
        val refreshedSlots = mutableSetOf<ArtworkSlot>()

        /**
         * Downloads the best candidate that actually yields usable bytes. A provider is
         * only a proposal: if P18 returns a URL but its download fails, the next ranked
         * source must still be tried instead of leaving the slot empty.
         */
        suspend fun saveBestCandidate(
            slot: ArtworkSlot,
            focus: ArtworkFocus,
            comparator: Comparator<ArtistArtworkCandidate>,
            stageAssetIds: Set<String>,
        ): Boolean {
            var lastError: String? = null
            val attempted = attemptedBySlot.getOrPut(slot) { mutableSetOf() }
            val inStage = attemptedInStageBySlot.getOrPut(slot) { mutableSetOf() }
            for (candidate in distinct.values.filter { it.stableAssetId in stageAssetIds }
                .sortedWith(comparator)) {
                if (!ArtistArtworkStagePolicy.canTryAcrossStages(attempted, inStage,
                        candidate.stableAssetId)) continue
                attempted += candidate.stableAssetId
                inStage += candidate.stableAssetId
                val downloaded = download(candidate)
                if (downloaded.isFailure) {
                    lastError = downloaded.exceptionOrNull()?.message ?: lastError
                    continue
                }
                val stored = try {
                    if (expectedAutoSource == null) {
                        store.save(anchor, candidate, slot, downloaded.getOrThrow(), focus, manuallyChosen = false)
                    } else {
                        store.saveAutomated(anchor, candidate, slot, downloaded.getOrThrow(), focus,
                            expectedAutoSource, expectedAutoEpoch)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    lastError = error.message ?: lastError
                    continue
                }
                saved[slot] = stored
                refreshedSlots += slot
                return true
            }
            if (lastError != null) cacheError = cacheError ?: lastError
            return false
        }

        // K39: resolve a provider and persist its verified artwork BEFORE consulting
        // the next one. P18 may fill both slots with a single download. P180,
        // Wikipedia, P373 and P935 are paid only for slots still missing.
        fun needs(slot: ArtworkSlot): Boolean {
            val requested = if (slot == ArtworkSlot.ARTIST_THUMB) needThumb else needHero
            if (!requested || slot in refreshedSlots) return false
            // In force-refresh mode, an old READY is not proof that a new candidate
            // was saved during THIS resolution; keep the old bytes as fallback.
            return forceRefresh || saved[slot]?.status != ArtworkLoadStatus.READY
        }
        // An extra P180 search page is paid only when the first did not fill the
        // slots. Keep it before Wikipedia/P373/P935 and behind the same QID gate.
        val primary = automaticProviders.filterNot { it is DisabledArtworkProvider }
        val stages = primary.flatMap { provider ->
            if (provider.source == ArtworkSource.COMMONS_P180) listOf(
                provider, CommonsP180ContinuationProvider(http, commons),
            ) else listOf(provider)
        } + listOf(
            CommonsCategoryArtworkProvider(http, wikidata, commons),
            CommonsGalleryArtworkProvider(http, wikidata, commons),
        )
        ArtistArtworkStagePolicy.visitUntilComplete(
            stages,
            hasMissing = { needs(ArtworkSlot.ARTIST_THUMB) || needs(ArtworkSlot.ARTIST_HERO) },
        ) { provider ->
            val result = try {
                provider.findCandidates(anchor, ArtworkSlot.ARTIST_THUMB)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure(error)
            }
            when (result) {
                is ProviderArtworkResult.Available -> {
                    attemptedInStageBySlot.clear()
                    val valid = result.items.filter { allowedArtworkCandidate(it, anchor) }
                    // Canonical Commons ID is the deduplication authority across
                    // P18/P180/Wikipedia/P373/P935; a repeated URL is not new work.
                    valid.forEach { distinct.putIfAbsent(it.stableAssetId, it) }
                    val stageAssetIds = valid.mapTo(mutableSetOf()) { it.stableAssetId }
                    outcomes += ArtworkProviderOutcome(provider.source,
                        if (valid.isEmpty()) ArtworkProviderStatus.NO_MATCH else ArtworkProviderStatus.AVAILABLE,
                        if (valid.isEmpty()) "Sin candidato con identidad y licencia verificadas" else null,
                        valid.size)
                    if (needs(ArtworkSlot.ARTIST_THUMB)) {
                        saveBestCandidate(ArtworkSlot.ARTIST_THUMB, ArtworkFocus(0.5f, 0.5f),
                            artworkRankingComparator(ArtworkSlot.ARTIST_THUMB), stageAssetIds)
                    }
                    if (needs(ArtworkSlot.ARTIST_HERO)) {
                        saveBestCandidate(ArtworkSlot.ARTIST_HERO, ArtworkFocus(0.5f, 0.45f),
                            heroComparator, stageAssetIds)
                    }
                }
                is ProviderArtworkResult.NoMatch -> outcomes += ArtworkProviderOutcome(
                    provider.source, ArtworkProviderStatus.NO_MATCH, "Sin coincidencias")
                is ProviderArtworkResult.Failure -> outcomes += ArtworkProviderOutcome(
                    provider.source, ArtworkProviderStatus.FAILURE, result.type, 0)
                is ProviderArtworkResult.Disabled -> outcomes += ArtworkProviderOutcome(
                    provider.source, ArtworkProviderStatus.DISABLED, result.reason)
            }
        }
        // Retain the product policy in the report, without making any HTTP calls.
        automaticProviders.filterIsInstance<DisabledArtworkProvider>().forEach { provider ->
            outcomes += ArtworkProviderOutcome(provider.source, ArtworkProviderStatus.DISABLED,
                "Proveedor desactivado")
        }
        if (needThumb || needHero) {
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
        ArtistEnrichmentDiagnostics.event("AE_RECOVERY_COMPLETED", status.name)
        return ArtworkResolutionReport(
            status = status,
            outcomes = outcomes,
            selectedAssets = ArtworkSlot.entries.associateWith { saved[it]?.assetId },
            selections = saved,
            message = if (status == ArtworkLoadStatus.READY) null
                else cacheError ?: outcomes.firstOrNull { it.status == ArtworkProviderStatus.FAILURE }?.reason,
        )
    }

    suspend fun searchManual(anchor: ArtistIdentityAnchor, query: String): ProviderArtworkResult {
        val results = listOf(
            openverse.search(anchor, query),
            theAudioDb.search(anchor, query),
            fanartTv.search(anchor),
        )
        val available = results.filterIsInstance<ProviderArtworkResult.Available>()
            .flatMap { it.items }
        if (available.isNotEmpty()) return ProviderArtworkResult.Available(available)
        // A transport failure on one provider must not hide a clean answer from
        // another, but it must not be reported as "no match" either.
        val failure = results.filterIsInstance<ProviderArtworkResult.Failure>().firstOrNull()
        return failure ?: ProviderArtworkResult.NoMatch
    }

    /**
     * Explicitly chosen manual candidate. An Openverse/Flickr candidate only becomes a
     * file once its origin page confirms the exact licence; a TheAudioDB or fanart.tv
     * candidate carries no licence to confirm and therefore keeps
     * `UNDECLARED_LICENSE` for ever.
     * Neither path is automatic, and neither replaces a verified slot without this
     * direct user action.
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
            val undeclared = candidate.source == ArtworkSource.THEAUDIODB ||
                candidate.source == ArtworkSource.FANART_TV
            if (!undeclared) {
                val html = http.readText(candidate.canonicalSourcePage, setOf("www.flickr.com"))
                if (!originPageConfirmsLicense(candidate.canonicalSourcePage, candidate.licenseUrl, html)) {
                    return@withAnchorLock reportFailure(anchor, ArtworkLoadStatus.REJECTED, "El origen no confirma la licencia")
                }
            }
            val downloaded = downloader.download(candidate)
            val verified = candidate.copy(
                originalWidth = downloaded.width,
                originalHeight = downloaded.height,
                mimeType = downloaded.mimeType,
                evidence = ArtworkEvidence.USER_SELECTED,
                rights = if (undeclared) ArtworkRights.UNDECLARED_LICENSE else ArtworkRights.VERIFIED_ALLOWED,
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
                status = when (selections.values.count { it.status == ArtworkLoadStatus.READY }) {
                    2 -> ArtworkLoadStatus.READY
                    1 -> ArtworkLoadStatus.PARTIAL
                    else -> ArtworkLoadStatus.CACHE_ERROR
                },
                outcomes = listOf(ArtworkProviderOutcome(candidate.source,
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

    /** Re-reads persisted selections after a failed mutation; the UI never guesses saved state. */
    suspend fun loadSelections(anchor: ArtistIdentityAnchor): List<ArtistArtworkSelection> =
        store.load(anchor)

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
