package org.michimusic.mobile.artist.artwork

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.MusicRootPreferences
import org.michimusic.data.repository.MusicRootStamp
import java.util.concurrent.TimeUnit

/** Single shot, exact association work; no polling, service or audio read. */
class ArtistArtworkAutoScheduler(private val context: Context) {
    private val app = context.applicationContext

    /** Weak Sync/AirCat hints may flush existing exact-key jobs, never invent a new one. */
    suspend fun onTrackCommitted(libraryId: String, artistCredit: String): Unit = withContext(Dispatchers.IO) {
        if (!isEnabled(app) || isPaused(app)) return@withContext
        if (libraryId.isBlank() || libraryId == "SCOPE_ALL" ||
            ArtistArtworkAutoWorkKey.creditKey(artistCredit) == null) return@withContext
        dispatchPending()
    }

    /**
     * This callback is invoked ONLY after a semantic Room operation has committed a
     * confirmed artist association or its verified profile. A credit string is never
     * used to identify an artist or infer an album-credit binding.
     *
     * The outbox insertion rechecks association, MBID, QID and exact bound album INSIDE
     * a Room transaction, then WorkManager handles networking outside the transaction.
     */
    suspend fun onVerifiedAssociationCommitted(
        libraryId: String, creditKey: String, associationId: String,
        enteredRoot: MusicRootStamp, mayCaptureBindingProof: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        if (libraryId != MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID ||
            !isEnabled(app) || isPaused(app)) return@withContext
        if (MusicRootPreferences(app).currentRootStamp() != enteredRoot) return@withContext
        val source = enteredRoot.sourceId.takeIf { it.isNotBlank() } ?: return@withContext
        val fingerprint = ArtistArtworkDeferredWakePolicy.sourceFingerprint(libraryId, source)
            ?: return@withContext
        if (ArtistArtworkAutoWorkKey.uniqueName(libraryId, creditKey, associationId, fingerprint) == null) {
            return@withContext
        }
        val db = ArtistEnrichmentPersistence.database(app)
        val identity = db.artistEnrichmentDao()
        val journal = db.artistArtworkCatalogJournalDao()
        val association = identity.getDurableAssociation(libraryId, creditKey, associationId)
            ?: return@withContext
        val record = identity.get(libraryId, creditKey, association.identityAssociationId)
        val count = identity.countConfirmedBindingsForAssociation(libraryId, creditKey, associationId)
        // A new explicitly confirmed MBID may not have its Wikidata QID yet.
        // Capture the user-authorized SOURCE proof now, without fetching art.
        // A later profile refresh may enqueue only against that preexisting proof.
        val anchor = ArtistArtworkAutoEligibility.anchor(
            libraryId, creditKey, associationId, listOf(association), record, count,
        )
        val baseline = journal.baseline(libraryId)
            ?.takeIf { it.sourceId == source } ?: return@withContext
        db.withTransaction {
            if (!isEnabled(app) || isPaused(app) ||
                MusicRootPreferences(app).currentRootStamp() != enteredRoot
            ) return@withTransaction
            val current = identity.getDurableAssociation(libraryId, creditKey, associationId)
                ?: return@withTransaction
            if (current != association ||
                identity.get(libraryId, creditKey, current.identityAssociationId) != record
            ) return@withTransaction
            // A proof is produced only for a binding explicitly confirmed after
            // the complete active-source catalog observation. Older legacy rows
            // cannot be silently attributed to whichever SAF root is active now.
            if (journal.baseline(libraryId) != baseline) return@withTransaction
            val now = System.currentTimeMillis()
            if (mayCaptureBindingProof) {
                journal.captureBindingSourceProofs(libraryId, creditKey, associationId,
                    source, baseline.updatedAtMs, now)
            }
            journal.reviveProvenSourceEvents(libraryId, source, creditKey, associationId, now)
            if (anchor == null || anchor.confirmedMbid.isBlank() || anchor.verifiedQid.isNullOrBlank()) {
                return@withTransaction // Source proof recorded, zero provider work.
            }
            val albumKey = journal.firstProvenBoundAlbumKey(libraryId, creditKey,
                associationId, source) ?: return@withTransaction
            if (journal.countVerifiedSourceBinding(libraryId, source, albumKey,
                    creditKey, associationId) < 1 ||
                identity.getAlbumCreditBinding(libraryId, albumKey, creditKey)?.associationId != associationId
            ) return@withTransaction
            // IGNORE never reopens a cooldown or a completed record. Explicit review
            // can re-evaluate it; a profile refresh cannot defeat negative caching.
            val inserted = journal.insertOutboxIfAbsent(org.michimusic.data.artist.ArtistArtworkAutoOutboxRecord(
                libraryId = libraryId,
                creditKey = creditKey,
                associationId = associationId,
                albumKey = albumKey,
                sourceId = source,
                updatedAtMs = now,
            ))
            if (inserted < 0L) {
                // Explicitly verified identity is new evidence. It can re-arm a
                // previous NEEDS_IDENTITY, but cannot bypass provider cooldowns.
                journal.reviveVerifiedEvent(libraryId, creditKey, associationId, albumKey, source, now)
            }
        }
        // No work is enqueued if the root changed after the Room transaction.
        if (MusicRootPreferences(app).currentRootStamp() == enteredRoot) {
            dispatchPending(requiredRootStamp = enteredRoot)
        }
    }

    /** Dispatcher replays only existing durable outbox entries; it never reads music. */
    suspend fun dispatchPending(requiredRootStamp: MusicRootStamp? = null): Unit = withContext(Dispatchers.IO) {
        if (!isEnabled(app) || isPaused(app)) return@withContext
        val journal = ArtistEnrichmentPersistence.database(app).artistArtworkCatalogJournalDao()
        val now = System.currentTimeMillis()
        val enteredRoot = MusicRootPreferences(app).currentRootStamp()
        if (requiredRootStamp != null && requiredRootStamp != enteredRoot) return@withContext
        val source = enteredRoot.sourceId
        if (source.isBlank()) return@withContext
        val fingerprint = ArtistArtworkDeferredWakePolicy.sourceFingerprint(
            MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID, source,
        ) ?: return@withContext
        journal.holdUnprovenEventsForRoot(
            MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID, source, now,
        )
        if (MusicRootPreferences(app).currentRootStamp() != enteredRoot) return@withContext
        // WorkManager KEEP returns without creating a job when the same unique work
        // is already ENQUEUED/RUNNING. Those jobs must not consume the dispatch cap.
        // One WorkManager snapshot is cheaper than 128 individual unique-work queries.
        // Fail closed if unavailable: the durable Room PENDING rows remain retryable.
        val workManager = WorkManager.getInstance(app)
        val activeQuery = WorkQuery.Builder.fromTags(listOf(TAG))
            .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED))
            .build()
        val active = try {
            workManager.getWorkInfos(activeQuery).get(5, TimeUnit.SECONDS)
                .asSequence()
                .filter { ArtistArtworkDispatchFairness.isActiveWorkState(it.state.name) }
                .flatMap { it.tags.asSequence() }
                .filter { it.startsWith("michi.artist.auto.unique:") }
                .toMutableSet()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return@withContext
        } catch (_: Exception) {
            return@withContext
        }
        var scheduled = 0
        var afterReadyAtMs = -1L
        var afterCreditKey = ""
        var afterAssociationId = ""
        for (pageNumber in 0 until ArtistArtworkDispatchFairness.MAX_PAGES) {
            if (MusicRootPreferences(app).currentRootStamp() != enteredRoot ||
                !isEnabled(app) || isPaused(app)) return@withContext
            val page = journal.provenPendingDispatchPageForRoot(
                MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID, source,
                ArtistArtworkDispatchFairness.MAX_PAGE_SIZE, now,
                afterReadyAtMs, afterCreditKey, afterAssociationId,
            )
            if (page.isEmpty()) break
            for (row in page) {
                if (MusicRootPreferences(app).currentRootStamp() != enteredRoot ||
                    !isEnabled(app) || isPaused(app)) return@withContext
                val cursor = row.readyAtMs
                afterReadyAtMs = cursor
                afterCreditKey = row.creditKey
                afterAssociationId = row.associationId
                val name = ArtistArtworkAutoWorkKey.uniqueName(
                    row.libraryId, row.creditKey, row.associationId, fingerprint) ?: continue
                val tag = ArtistArtworkDispatchFairness.activeTag(name, enteredRoot.epoch)
                if (!ArtistArtworkDispatchFairness.eligible(tag, active, emptySet())) continue
                if (journal.countProvenPendingForAssociation(row.libraryId, source,
                        row.creditKey, row.associationId, now) < 1) continue
                val notBefore = nextRetryAt(app, name)
                if (notBefore != null && notBefore > now) {
                    if (MusicRootPreferences(app).currentRootStamp() != enteredRoot) return@withContext
                    journal.deferPendingForAssociation(row.libraryId, row.creditKey,
                        row.associationId, source, notBefore)
                    continue
                }
                val accepted = try {
                    enqueue(row.libraryId, row.creditKey, row.associationId, source, enteredRoot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false // leave durable PENDING untouched; retry on next authorized event
                }
                if (accepted) {
                    if (tag != null) active.add(tag)
                    if (++scheduled >= ArtistArtworkDispatchFairness.MAX_NEW_WORK) break
                }
            }
            if (scheduled >= ArtistArtworkDispatchFairness.MAX_NEW_WORK ||
                page.size < ArtistArtworkDispatchFairness.MAX_PAGE_SIZE) break
        }
        // A future cooldown must revive itself even with no new library events.
        // Only the earliest future deadline is scheduled, coalesced into one hour.
        val firstFuture = journal.provenNextDeferredDueAtForRoot(
            MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID, source, now,
        )
        if (firstFuture != null && MusicRootPreferences(app).currentRootStamp() == enteredRoot) {
            scheduleDeferredWake(MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID, enteredRoot, firstFuture, now)
        }
    }

    private fun scheduleDeferredWake(libraryId: String, rootStamp: MusicRootStamp, dueAtMs: Long, nowMs: Long) {
        if (!isEnabled(app) || isPaused(app) ||
            MusicRootPreferences(app).currentRootStamp() != rootStamp) return
        val sourceId = rootStamp.sourceId
        val wakeAt = ArtistArtworkDeferredWakePolicy.wakeTime(nowMs, dueAtMs) ?: return
        val name = ArtistArtworkDeferredWakePolicy.uniqueWorkName(libraryId, sourceId, wakeAt)
            ?: return
        val fingerprint = ArtistArtworkDeferredWakePolicy.sourceFingerprint(libraryId, sourceId)
            ?: return
        val request = OneTimeWorkRequestBuilder<ArtistArtworkDeferredWakeWorker>()
            .setInputData(workDataOf(
                ArtistArtworkDeferredWakeWorker.LIBRARY_ID to libraryId,
                ArtistArtworkDeferredWakeWorker.SOURCE_FINGERPRINT to fingerprint,
                ArtistArtworkDeferredWakeWorker.ROOT_EPOCH to rootStamp.epoch,
            ))
            .setInitialDelay((wakeAt - nowMs).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        // Returning A->B->A must not inherit the delayed wake from the prior A.
        if (MusicRootPreferences(app).currentRootStamp() != rootStamp ||
            !isEnabled(app) || isPaused(app)) return
        WorkManager.getInstance(app).enqueueUniqueWork(
            "$name.e${rootStamp.epoch}", ExistingWorkPolicy.KEEP, request)
    }

    /** Manual button only. A normal event NEVER bypasses the negative-cache cooldown.
     * At most MAX_DISPATCH associations and one active root are affected per action.
     */
    suspend fun retryPendingExplicitly(): Int = withContext(Dispatchers.IO) {
        if (!isEnabled(app) || isPaused(app)) return@withContext 0
        val enteredRoot = MusicRootPreferences(app).currentRootStamp()
        val source = enteredRoot.sourceId
        if (source.isBlank()) return@withContext 0
        val libraryId = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
        val dao = ArtistEnrichmentPersistence.database(app).artistArtworkCatalogJournalDao()
        val fingerprint = ArtistArtworkDeferredWakePolicy.sourceFingerprint(libraryId, source)
            ?: return@withContext 0
        val activeQuery = WorkQuery.Builder.fromTags(listOf(TAG))
            .addStates(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED))
            .build()
        val active = try {
            WorkManager.getInstance(app).getWorkInfos(activeQuery).get(5, TimeUnit.SECONDS)
                .asSequence()
                .filter { ArtistArtworkDispatchFairness.isActiveWorkState(it.state.name) }
                .flatMap { it.tags.asSequence() }
                .toMutableSet()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return@withContext 0
        } catch (_: Exception) {
            return@withContext 0
        }
        // The user action has the same 8-page/16-new-job budget as dispatcher.
        // A previously ENQUEUED job does not consume a manual retry slot.
        var count = 0
        var afterReadyAtMs = -1L
        var afterCreditKey = ""
        var afterAssociationId = ""
        for (pageNumber in 0 until ArtistArtworkDispatchFairness.MAX_PAGES) {
            if (!isEnabled(app) || isPaused(app) ||
                MusicRootPreferences(app).currentRootStamp() != enteredRoot) break
            val page = dao.provenManualRetryPageForRoot(libraryId, source,
                ArtistArtworkDispatchFairness.MAX_PAGE_SIZE,
                afterReadyAtMs, afterCreditKey, afterAssociationId)
            if (page.isEmpty()) break
            for (row in page) {
                if (!isEnabled(app) || isPaused(app) ||
                    MusicRootPreferences(app).currentRootStamp() != enteredRoot) return@withContext count
                afterReadyAtMs = row.readyAtMs
                afterCreditKey = row.creditKey
                afterAssociationId = row.associationId
                val name = ArtistArtworkAutoWorkKey.uniqueName(
                    row.libraryId, row.creditKey, row.associationId, fingerprint) ?: continue
                val tag = ArtistArtworkDispatchFairness.activeTag(name, enteredRoot.epoch)
                if (!ArtistArtworkDispatchFairness.eligible(tag, active, emptySet())) continue
                // Explicit retry clears only a selected artist cooldown and cannot
                // rearm terminal READY/CACHED, absent proof, or another source.
                if (!prefs(app).edit().remove(RETRY_PREFIX + name).commit()) continue
                if (MusicRootPreferences(app).currentRootStamp() != enteredRoot) return@withContext count
                val touched = dao.activatePendingForAssociation(
                    row.libraryId, source, row.creditKey, row.associationId,
                    System.currentTimeMillis(),
                )
                if (touched == 0) continue
                val accepted = try {
                    enqueue(row.libraryId, row.creditKey, row.associationId, source, enteredRoot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { false }
                if (accepted) {
                    if (tag != null) active.add(tag)
                    if (++count >= MAX_DISPATCH) return@withContext count
                }
            }
            if (page.size < ArtistArtworkDispatchFairness.MAX_PAGE_SIZE) break
        }
        count
    }

    internal fun enqueue(
        libraryId: String, creditKey: String, associationId: String, sourceId: String,
        requiredRootStamp: MusicRootStamp? = null,
    ): Boolean {
        if (!isEnabled(app) || isPaused(app)) return false
        val rootStamp = MusicRootPreferences(app).currentRootStamp()
        if (sourceId != rootStamp.sourceId ||
            (requiredRootStamp != null && requiredRootStamp != rootStamp)) return false
        val fingerprint = ArtistArtworkDeferredWakePolicy.sourceFingerprint(libraryId, sourceId) ?: return false
        val name = ArtistArtworkAutoWorkKey.uniqueName(libraryId, creditKey, associationId, fingerprint) ?: return false
        if (nextRetryAt(app, name)?.let { it > System.currentTimeMillis() } == true) return false
        val network = if (unmeteredOnly(app)) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<ArtistArtworkAutoWorker>()
            .setInputData(workDataOf(
                ArtistArtworkAutoWorker.LIBRARY_ID to libraryId,
                ArtistArtworkAutoWorker.CREDIT_KEY to creditKey,
                ArtistArtworkAutoWorker.ASSOCIATION_ID to associationId,
                ArtistArtworkAutoWorker.SOURCE_FINGERPRINT to fingerprint,
                ArtistArtworkAutoWorker.ROOT_EPOCH to rootStamp.epoch,
            ))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(network)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(requireNotNull(ArtistArtworkDispatchFairness.activeTag(name, rootStamp.epoch)))
            .build()
        // KEEP must be scoped to the generation, not just the source fingerprint:
        // an old A job waiting during A->B->A must not suppress the new A job.
        // Cooldown remains associated with the stable source-scoped base `name`.
        if (MusicRootPreferences(app).currentRootStamp() != rootStamp ||
            !isEnabled(app) || isPaused(app)) return false
        WorkManager.getInstance(app).enqueueUniqueWork(
            "$name.e${rootStamp.epoch}", ExistingWorkPolicy.KEEP, request)
        return true // API accepted; persistence is verified by WorkManager itself
    }

    /** Control transitions are serialized across scheduler instances and workers. */
    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        ArtistArtworkAutoControlGate.guarding {
            ArtistArtworkWriteGate.guarding {
                check(prefs(app).edit().putBoolean(KEY_ENABLED, enabled).commit())
            }
            if (!enabled) {
                // Do not let an older cancellation run after a newer opt-in.
                WorkManager.getInstance(app).cancelAllWorkByTag(TAG).result.get(10, TimeUnit.SECONDS)
            } else if (!isPaused(app)) {
                // Consent never starts a full-library backfill; replay existing outbox only.
                dispatchPending()
            }
        }
    }

    suspend fun setPaused(paused: Boolean) = withContext(Dispatchers.IO) {
        ArtistArtworkAutoControlGate.guarding {
            ArtistArtworkWriteGate.guarding {
                check(prefs(app).edit().putBoolean(KEY_PAUSED, paused).commit())
            }
            if (paused) {
                WorkManager.getInstance(app).cancelAllWorkByTag(TAG).result.get(10, TimeUnit.SECONDS)
            } else if (isEnabled(app)) {
                dispatchPending() // Only existing durable events, never implicit backfill.
            }
        }
    }

    suspend fun setUnmeteredOnly(enabled: Boolean) = withContext(Dispatchers.IO) {
        ArtistArtworkAutoControlGate.guarding {
            val wasUnmetered = unmeteredOnly(app)
            if (wasUnmetered == enabled) return@guarding
            ArtistArtworkWriteGate.guarding {
                check(prefs(app).edit().putBoolean(KEY_UNMETERED, enabled).commit())
            }
            // Constraints on enqueued WorkRequests cannot change in-place.
            // Cancellation completes before a new dispatch observes new policy.
            WorkManager.getInstance(app).cancelAllWorkByTag(TAG).result.get(10, TimeUnit.SECONDS)
            if (isEnabled(app) && !isPaused(app)) {
                dispatchPending()
            }
        }
    }

    companion object {
        internal const val TAG = "michi.artist.artwork.auto"
        private const val KEY_ENABLED = "artist_auto_artwork_enabled"
        private const val KEY_PAUSED = "artist_auto_artwork_paused"
        private const val KEY_UNMETERED = "artist_auto_artwork_wifi_only"
        private const val PREFS = "michi_artist_enrichment_auto"
        private const val RETRY_PREFIX = "cooldown_"
        private const val MAX_COOLDOWN_ENTRIES = 256
        private const val MAX_DISPATCH = 16
        internal const val NO_IMAGE_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000
        internal const val PROVIDER_ERROR_COOLDOWN_MS = 24L * 60 * 60 * 1000
        private fun prefs(context: Context) = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
        fun isPaused(context: Context): Boolean = prefs(context).getBoolean(KEY_PAUSED, false)
        fun unmeteredOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_UNMETERED, true)

        internal fun nextRetryAt(context: Context, name: String): Long? =
            prefs(context).getLong(RETRY_PREFIX + name, -1L).takeIf { it > 0 }

        internal fun rememberMiss(context: Context, name: String, untilEpochMs: Long) {
            val storage = prefs(context)
            val editor = storage.edit()
            val now = System.currentTimeMillis()
            val expiries = storage.all.asSequence()
                .filter { it.key.startsWith(RETRY_PREFIX) }
                .mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }
                .toList()
            expiries.filter { it.second <= now }.forEach { editor.remove(it.first) }
            val active = expiries.filter { it.second > now }
            if (active.size >= MAX_COOLDOWN_ENTRIES && RETRY_PREFIX + name !in active.map { it.first }) {
                active.minByOrNull { it.second }?.let { editor.remove(it.first) }
            }
            // Persist negative-cache before the worker finishes; process death must
            // not silently drop the cooldown and trigger unnecessary provider calls.
            check(editor.putLong(RETRY_PREFIX + name, untilEpochMs).commit())
        }
    }
}
