package org.michimusic.data.metadata

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataBatchRunPolicy
import org.michimusic.core.metadata.MetadataBatchSequentialPolicy
import org.michimusic.core.metadata.MetadataPrepareTicket
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.data.repository.ExtendedTrackMetadataRepository

/**
 * ME-10Z: real, opt-in SEQUENTIAL private preparation. This component never
 * writes a source URI. It reuses the single V1 journal and durable V2 review-
 * bound batch receipts; it cannot skip a stage or auto-resume on process start.
 * One explicit user gesture may prepare AT MOST one track. There is no loop
 * that repeatedly invokes the source-preparation coordinator.
 *
 * Public seam: the ME-02 album batch workbench in `:app` is a legitimate caller,
 * so the type (and its result vocabulary) must stay visible across the module
 * boundary. Returned rows never grant a source write.
 */
class MetadataBatchSequentialPreparationCoordinator(
    context: Context,
    repository: ExtendedTrackMetadataRepository,
) {
    private val preparer=MetadataPreparationCoordinator(context,repository)
    private val store=MetadataBatchPrivateReceiptStore(context)
    private val mutex=Mutex()

    enum class Refusal {
        ACTION_REQUIRED, INVALID_SELECTION, RECEIPT_UNTRUSTED,
        RECEIPT_UNAVAILABLE, PENDING_JOURNAL, WRONG_ORDER_OR_STATUS,
        PREPARATION_UNCERTAIN_KEEP_JOURNAL,
    }
    sealed interface Result {
        data class Blocked(val reason: Refusal) : Result
        data class Current(val rows: List<MetadataBatchRunPolicy.FileResult>,
            val summary: MetadataBatchRunPolicy.Summary) : Result
        data class PreparedPrivateOnly(val transactionId: String,
            val rows: List<MetadataBatchRunPolicy.FileResult>) : Result
        data class RefusedAndRecorded(val index: Int,
            val reason: org.michimusic.core.metadata.MetadataPrepareRejection,
            val rows: List<MetadataBatchRunPolicy.FileResult>) : Result
    }

    /** Explicit inspection/creation. Returning Current never grants commit. */
    suspend fun inspect(batchId: String, snapshots: List<MetadataSnapshot>,
        reviews: List<MetadataReview>, userOpened: Boolean): Result =
        withContext(Dispatchers.IO) { mutex.withLock {
            if (!userOpened) return@withLock Result.Blocked(Refusal.ACTION_REQUIRED)
            val rows=loadOrInitialize(batchId,snapshots,reviews)
                ?: return@withLock Result.Blocked(Refusal.RECEIPT_UNTRUSTED)
            val summary=MetadataBatchRunPolicy.summarize(snapshots,reviews,rows)
                as? MetadataBatchRunPolicy.Result.Ready
                ?: return@withLock Result.Blocked(Refusal.INVALID_SELECTION)
            Result.Current(summary.rows,summary.summary)
        } }

    /**
     * A stage that is explicitly discarded after review must be recorded
     * before the next row can be prepared. The existing journal performs the
     * only deletion and refuses discard for any mutation/unknown phase.
     */
    suspend fun discardPrivateAndAdvance(batchId: String, snapshots: List<MetadataSnapshot>,
        reviews: List<MetadataReview>, index: Int,transactionId: String,
        userConfirmed: Boolean,ownerStillCurrent:()->Boolean):Result =
        withContext(Dispatchers.IO) { mutex.withLock {
            if (!userConfirmed) return@withLock Result.Blocked(Refusal.ACTION_REQUIRED)
            val rows=loadOrInitialize(batchId,snapshots,reviews)
                ?: return@withLock Result.Blocked(Refusal.RECEIPT_UNTRUSTED)
            if (index !in rows.indices ||
                rows[index].state != MetadataBatchRunPolicy.State.PRIVATE_STAGE_READY ||
                rows[index].transactionId != transactionId || !ownerStillCurrent())
                return@withLock Result.Blocked(Refusal.WRONG_ORDER_OR_STATUS)
            if (!preparer.discardPrepared(transactionId,true))
                return@withLock Result.Blocked(Refusal.PENDING_JOURNAL)
            val journalClear=preparer.inspectPending() == MetadataPreparationCoordinator.Pending.Clear
            val advanced=MetadataBatchSequentialPolicy.recordPrivateDiscard(
                rows,index,transactionId,journalClear)
                as? MetadataBatchSequentialPolicy.Transition.Updated
                ?: return@withLock Result.Blocked(Refusal.PENDING_JOURNAL)
            if (store.save(batchId,snapshots,advanced.rows,reviews) !=
                MetadataBatchPrivateReceiptStore.StoreResult.STORED)
                return@withLock Result.Blocked(Refusal.RECEIPT_UNAVAILABLE)
            val summary=MetadataBatchRunPolicy.summarize(snapshots,reviews,advanced.rows)
                as? MetadataBatchRunPolicy.Result.Ready
                ?: return@withLock Result.Blocked(Refusal.INVALID_SELECTION)
            Result.Current(summary.rows,summary.summary)
        } }

    /** One exact, user-selected row. All owner/playback checks are live callbacks. */
    suspend fun prepareOne(batchId: String, snapshots: List<MetadataSnapshot>,
        reviews: List<MetadataReview>, expectedBytes: List<Long>, index: Int,
        explicitUserAction: Boolean, ownerStillCurrent: () -> Boolean,
        playbackClosed: () -> Boolean): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!explicitUserAction) return@withLock Result.Blocked(Refusal.ACTION_REQUIRED)
            if (expectedBytes.size != snapshots.size ||
                expectedBytes.any { it !in 12..(1024L*1024*1024) })
                return@withLock Result.Blocked(Refusal.INVALID_SELECTION)
            val rows=loadOrInitialize(batchId,snapshots,reviews)
                ?: return@withLock Result.Blocked(Refusal.RECEIPT_UNTRUSTED)
            // A new preparation is illegal while the V1 journal has anything
            // requiring inspection, including an incomplete backup or stage.
            val journalClear=preparer.inspectPending() == MetadataPreparationCoordinator.Pending.Clear
            when (MetadataBatchSequentialPolicy.next(snapshots,reviews,rows,index,
                explicitUserAction,journalClear)) {
                is MetadataBatchSequentialPolicy.Verdict.Blocked ->
                    return@withLock Result.Blocked(if (!journalClear) Refusal.PENDING_JOURNAL
                        else Refusal.WRONG_ORDER_OR_STATUS)
                is MetadataBatchSequentialPolicy.Verdict.Eligible -> Unit
            }
            val snapshot=snapshots[index]
            val prepared=try {
                preparer.prepare(MetadataPreparationCoordinator.Request(
                    MetadataPrepareTicket(snapshot.target.sourceId,snapshot.target.trackId,
                        snapshot.target.uri,expectedBytes[index],true,
                        playbackClosed(),ownerStillCurrent()),
                    reviews[index],playbackClosed,ownerStillCurrent))
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) {
                // Unexpected exception might leave SNAPSHOTTING/BACKUP_DURABLE
                // evidence. Never mark the row as safely skipped in that case.
                return@withLock Result.Blocked(Refusal.PREPARATION_UNCERTAIN_KEEP_JOURNAL)
            }
            val result=MetadataBatchSequentialPolicy.record(rows,index,
                (prepared as? MetadataPreparationCoordinator.Outcome.Prepared)?.transactionId)
                as? MetadataBatchSequentialPolicy.Transition.Updated
                ?: return@withLock Result.Blocked(Refusal.RECEIPT_UNTRUSTED)
            if (store.save(batchId,snapshots,result.rows,reviews) !=
                MetadataBatchPrivateReceiptStore.StoreResult.STORED)
                return@withLock Result.Blocked(Refusal.RECEIPT_UNAVAILABLE)
            when (prepared) {
                is MetadataPreparationCoordinator.Outcome.Prepared ->
                    Result.PreparedPrivateOnly(prepared.transactionId,result.rows)
                is MetadataPreparationCoordinator.Outcome.Refused ->
                    Result.RefusedAndRecorded(index,prepared.reason,result.rows)
            }
        }
    }

    private suspend fun loadOrInitialize(batchId: String,
        snapshots: List<MetadataSnapshot>,reviews: List<MetadataReview>):
        List<MetadataBatchRunPolicy.FileResult>? {
        val baseline=MetadataBatchSequentialPolicy.initial(reviews,snapshots) ?: return null
        return when (val read=store.load(batchId,snapshots,reviews)) {
            is MetadataBatchPrivateReceiptStore.Read.Verified -> {
                val rows=read.record.rows.mapIndexed { i,row ->
                    MetadataBatchRunPolicy.FileResult(i,snapshots[i].target,row.status,row.transactionId)
                }
                if (MetadataBatchRunPolicy.summarize(snapshots,reviews,rows)
                    is MetadataBatchRunPolicy.Result.Ready) rows else null
            }
            MetadataBatchPrivateReceiptStore.Read.NotFound ->
                if (store.save(batchId,snapshots,baseline,reviews) ==
                    MetadataBatchPrivateReceiptStore.StoreResult.STORED) baseline else null
            MetadataBatchPrivateReceiptStore.Read.UntrustedKeepExisting -> null
        }
    }
}
