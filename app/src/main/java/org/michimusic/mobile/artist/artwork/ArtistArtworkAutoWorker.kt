package org.michimusic.mobile.artist.artwork

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.michimusic.data.repository.MusicRootPreferences
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence

/**
 * Event-only, bounded provider recovery. Every pending album-credit row is checked
 * against authoritative Room binding immediately before accessing Wikimedia.
 */
class ArtistArtworkAutoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val libraryId = inputData.getString(LIBRARY_ID).orEmpty()
        val creditKey = inputData.getString(CREDIT_KEY).orEmpty()
        val associationId = inputData.getString(ASSOCIATION_ID).orEmpty()
        val fingerprint = inputData.getString(SOURCE_FINGERPRINT).orEmpty()
        val enteredEpoch = inputData.getLong(ROOT_EPOCH, -1L)
        val rootStamp = MusicRootPreferences(applicationContext).currentRootStamp()
        if (enteredEpoch < 0L || rootStamp.epoch != enteredEpoch) return@withContext Result.success()
        val workName = ArtistArtworkAutoWorkKey.uniqueName(libraryId, creditKey, associationId, fingerprint)
            ?: return@withContext Result.success()
        val currentSource = rootStamp.sourceId
        if (ArtistArtworkDeferredWakePolicy.sourceFingerprint(libraryId, currentSource) != fingerprint) {
            // A former root's unique job cannot consume or close the new root's outbox.
            return@withContext Result.success()
        }
        if (!ArtistArtworkAutoScheduler.isEnabled(applicationContext) ||
            ArtistArtworkAutoScheduler.isPaused(applicationContext) || isStopped
        ) return@withContext Result.success()
        try {
            val outcome = providerMutex.withLock {
                perform(libraryId, creditKey, associationId, workName, currentSource, enteredEpoch)
            }
            // Opportunistically drain the durable outbox without a periodic worker.
            if (!isStopped) {
                try { ArtistArtworkAutoScheduler(applicationContext).dispatchPending(
                    requiredRootStamp = org.michimusic.data.repository.MusicRootStamp(currentSource, enteredEpoch)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* persisted outbox survives for next event */ }
            }
            outcome
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No former root generation may modify cooldowns or retry state after A->B->A.
            if (!sameRootEpoch(currentSource, enteredEpoch)) return@withContext Result.success()
            if (runAttemptCount < 2) Result.retry() else {
                // A permanent infrastructure failure must not be rescheduled on each
                // unrelated library event. Keep durable evidence for explicit retry.
                val until = System.currentTimeMillis() + ArtistArtworkAutoScheduler.PROVIDER_ERROR_COOLDOWN_MS
                ArtistArtworkAutoScheduler.rememberMiss(applicationContext, workName, until)
                try {
                    ArtistEnrichmentPersistence.database(applicationContext)
                        .artistArtworkCatalogJournalDao().deferPendingForAssociation(
                            libraryId, creditKey, associationId, currentSource, until,
                        )
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* durable evidence remains for reconciliation */ }
                // This work is terminal. Queue one local delayed wake for the next deadline.
                try { ArtistArtworkAutoScheduler(applicationContext).dispatchPending(
                    requiredRootStamp = org.michimusic.data.repository.MusicRootStamp(currentSource, enteredEpoch)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* no added media scan or fallback polling */ }
                Result.success()
            }
        }
    }

    private suspend fun perform(
        libraryId: String, creditKey: String, associationId: String, workName: String,
        expectedSourceId: String, expectedRootEpoch: Long,
    ): Result {
        if (!ArtistArtworkAutoScheduler.isEnabled(applicationContext) ||
            ArtistArtworkAutoScheduler.isPaused(applicationContext) || isStopped ||
            MusicRootPreferences(applicationContext).currentRootStamp() !=
                org.michimusic.data.repository.MusicRootStamp(expectedSourceId, expectedRootEpoch)
        ) return Result.success()

        val db = ArtistEnrichmentPersistence.database(applicationContext)
        val dao = db.artistEnrichmentDao()
        val journal = db.artistArtworkCatalogJournalDao()
        val associations = dao.getDurableAssociationsForCredit(libraryId, creditKey)
        val association = associations.singleOrNull { it.associationId == associationId }
        if (!sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()
        if (association == null) {
            journal.finishMissingAssociation(libraryId, creditKey, associationId,
                expectedSourceId, System.currentTimeMillis())
            return Result.success()
        }
        val now = System.currentTimeMillis()
        // Clean stale exact bindings before selecting due work. Otherwise 1024
        // orphaned album events can hide a valid one; KEEP cannot guarantee that
        // another worker will be created while this one is still running.
        if (!sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()
        journal.finishOrphanedPendingForRoot(
            libraryId, creditKey, associationId, expectedSourceId, now,
        )
        val pending = journal.provenPendingForRoot(libraryId, creditKey, associationId,
            expectedSourceId, now)
        val activeRoot = activeSourceId()
        if (activeRoot != expectedSourceId || !sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()
        val valid = mutableListOf<org.michimusic.data.artist.ArtistArtworkAutoOutboxRecord>()
        for (event in pending) {
            // Root changes may race this worker while it waits for Room. Never
            // consume a newer root's evidence, even if album/credit IDs match.
            if (!sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()
            val binding = dao.getAlbumCreditBinding(libraryId, event.albumKey, creditKey)
            if (binding?.associationId == associationId &&
                journal.countVerifiedSourceBinding(libraryId, expectedSourceId, event.albumKey,
                    creditKey, associationId) > 0
            ) {
                valid += event
            } else {
                if (!sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()
                journal.finish(libraryId, creditKey, associationId, event.albumKey,
                    expectedSourceId, "STALE_CONTEXT", now)
            }
        }
        // A weak Sync/AirCat hint is not an exact album-credit event. Do not perform
        // external work without at least one live, source-scoped pending binding.
        if (valid.isEmpty() || !sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()
        val bindings = dao.countConfirmedBindingsForAssociation(libraryId, creditKey, associationId)
        if (bindings < 1) return Result.success()
        val record = dao.get(libraryId, creditKey, association.identityAssociationId)
        val anchor = ArtistArtworkAutoEligibility.anchor(
            libraryId, creditKey, associationId, associations, record, bindings,
        ) ?: run {
            if (!sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()
            journal.finishBoundAssociation(libraryId, creditKey, associationId,
                activeRoot, "NEEDS_IDENTITY", System.currentTimeMillis())
            return Result.success()
        }
        val store = ArtistArtworkPersistence.from(applicationContext)
        val selections = store.load(anchor).map { selection ->
            try { verifyCachedArtwork(applicationContext, selection) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { selection.copy(status = ArtworkLoadStatus.CACHE_ERROR) }
        }
        // Room selection loading is MBID-scoped. A prior-MBID manual choice still
        // protects its slot even when this artist now has a different confirmed ID.
        val manualSlots = mutableSetOf<ArtworkSlot>()
        for (slot in ArtworkSlot.entries) {
            if (dao.countManualArtworkForSlot(libraryId, creditKey, associationId, slot.name) > 0) {
                manualSlots.add(slot)
            }
        }
        val connectivity = ArtistArtworkAutoNetwork.snapshot(applicationContext)
        val decision = ArtistArtworkAutoPolicy.evaluate(anchor, selections, AutoArtworkConditions(
            enabled = ArtistArtworkAutoScheduler.isEnabled(applicationContext),
            paused = ArtistArtworkAutoScheduler.isPaused(applicationContext),
            associationNeedsConfirmation = ArtistArtworkAutoContextPolicy.needsConfirmation(
                libraryId, creditKey, associationId, expectedSourceId, valid,
            ), // scoped ONLY to the exact verified pending album-credit(s)
            networkAvailable = connectivity.connected,
            unmeteredOnly = ArtistArtworkAutoScheduler.unmeteredOnly(applicationContext),
            onUnmeteredNetwork = connectivity.unmetered,
            batteryNotLow = true,
            storageNotLow = true,
            nowEpochMs = System.currentTimeMillis(),
            nextRetryAtEpochMs = ArtistArtworkAutoScheduler.nextRetryAt(applicationContext, workName),
        ), manualSlots = manualSlots)
        if (decision !is AutoArtworkDecision.Eligible) {
            if (decision == AutoArtworkDecision.Blocked(AutoArtworkBlock.NETWORK_UNAVAILABLE) ||
                decision == AutoArtworkDecision.Blocked(AutoArtworkBlock.BATTERY_LOW) ||
                decision == AutoArtworkDecision.Blocked(AutoArtworkBlock.STORAGE_LOW)) {
                // WorkManager normally enforces the constraints. A validated network
                // can still disappear between its check and this snapshot. A bounded
                // retry prevents a one-shot job from disappearing without another event.
                if (runAttemptCount < 2) return Result.retry()
                val until = System.currentTimeMillis() + ArtistArtworkAutoScheduler.PROVIDER_ERROR_COOLDOWN_MS
                journal.deferPendingForAssociation(libraryId, creditKey, associationId, expectedSourceId, until)
                ArtistArtworkAutoScheduler.rememberMiss(applicationContext, workName, until)
            }
            if (decision == AutoArtworkDecision.Blocked(AutoArtworkBlock.NOTHING_TO_FETCH)) {
                if (!sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()
                journal.finishBoundAssociation(libraryId, creditKey, associationId,
                    activeRoot, "CACHED", System.currentTimeMillis())
            }
            return Result.success()
        }
        if (!ArtistArtworkAutoScheduler.isEnabled(applicationContext) || isStopped ||
            !sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()
        // Every automatic write now rechecks the SAME binding, identity, QID and
        // manual slot inside a single Room transaction (see persistArtworkAutomated).
        // The root/consent are also rechecked immediately before entering it.
        val report = ArtistArtworkService.from(applicationContext)
            .resolveAutomated(anchor, expectedSourceId = activeRoot,
                expectedRootEpoch = expectedRootEpoch, protectedSlots = manualSlots)
        if (!sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()
        // A partial report may be explained solely by a protected, manually chosen
        // damaged file. In that case there is no remaining user-authorized slot to
        // fetch: do not create an endless retry for something auto mode cannot fix.
        if (report.status == ArtworkLoadStatus.PARTIAL) {
            val after = store.load(anchor).map { item ->
                try { verifyCachedArtwork(applicationContext, item) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { item.copy(status = ArtworkLoadStatus.CACHE_ERROR) }
            }
            val covered = mutableSetOf<ArtworkSlot>()
            for (slot in ArtworkSlot.entries) {
                if (after.any { it.slot == slot && (it.manuallyChosen || it.status == ArtworkLoadStatus.READY) } ||
                    dao.countManualArtworkForSlot(libraryId, creditKey, associationId, slot.name) > 0
                ) covered.add(slot)
            }
            if (covered.size == ArtworkSlot.entries.size) {
                journal.finishBoundAssociation(libraryId, creditKey, associationId,
                    activeRoot, "COMPLETED_MANUAL_PROTECTED", System.currentTimeMillis())
                return Result.success()
            }
        }
        // A PARTIAL or NO_IMAGE report can contain a temporary provider failure
        // or failed download. It is not evidence of a seven-day negative result.
        val transientFailure = report.outcomes.any { it.status == ArtworkProviderStatus.FAILURE } ||
            (!report.message.isNullOrBlank() && report.status in setOf(
                ArtworkLoadStatus.PARTIAL, ArtworkLoadStatus.NO_IMAGE,
            ))
        val action = ArtistArtworkAutoDisposition.decide(
            report.status, runAttemptCount, transientFailure,
        )
        if (!sameRootEpoch(expectedSourceId, expectedRootEpoch)) return Result.success()
        return when (action) {
            AutoArtworkNextAction.COMPLETE -> {
                journal.finishBoundAssociation(libraryId, creditKey, associationId,
                    activeRoot, "READY", System.currentTimeMillis())
                Result.success()
            }
            AutoArtworkNextAction.RETRY_WITH_WORK_MANAGER -> Result.retry()
            AutoArtworkNextAction.NEEDS_IDENTITY -> {
                journal.finishBoundAssociation(libraryId, creditKey, associationId,
                    activeRoot, "NEEDS_IDENTITY", System.currentTimeMillis())
                Result.success()
            }
            AutoArtworkNextAction.DEFER_NO_IMAGE,
            AutoArtworkNextAction.DEFER_PROVIDER_FAILURE -> {
                val cooldown = if (action == AutoArtworkNextAction.DEFER_NO_IMAGE) {
                    ArtistArtworkAutoScheduler.NO_IMAGE_COOLDOWN_MS
                } else ArtistArtworkAutoScheduler.PROVIDER_ERROR_COOLDOWN_MS
                val until = System.currentTimeMillis() + cooldown
                // Outbox remains PENDING so the next legitimate event / explicit retry
                // can fill the missing slot. No periodic polling is introduced.
                journal.deferPendingForAssociation(libraryId, creditKey, associationId, expectedSourceId, until)
                ArtistArtworkAutoScheduler.rememberMiss(applicationContext, workName, until)
                Result.success()
            }
        }
    }

    private fun activeSourceId(): String = MusicRootPreferences(applicationContext).currentRootStamp().sourceId

    private fun sameRootEpoch(source: String, epoch: Long): Boolean =
        MusicRootPreferences(applicationContext).currentRootStamp() ==
            org.michimusic.data.repository.MusicRootStamp(source, epoch)

    companion object {
        internal const val SOURCE_FINGERPRINT = "sourceFingerprint"
        internal const val ROOT_EPOCH = "rootEpoch"
        internal const val LIBRARY_ID = "libraryId"
        internal const val CREDIT_KEY = "creditKey"
        internal const val ASSOCIATION_ID = "associationId"
        // Process-wide serialization protects Wikimedia quotas across different WorkManager jobs.
        private val providerMutex = Mutex()
    }
}
