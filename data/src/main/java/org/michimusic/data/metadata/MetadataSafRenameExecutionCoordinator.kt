package org.michimusic.data.metadata

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import org.michimusic.core.metadata.MetadataSafRenameRecoveryPolicy
import org.michimusic.core.metadata.MetadataSafRenameSafetyPolicy

/**
 * ME-11O: physical SAF rename contract, intentionally uncertified and NOT wired
 * to Compose. Only after external provider-specific certification can this path
 * be considered. No commit, Room update or rollback is implied by the API call.
 */
internal object MetadataSafRenameCertification {
    const val PHYSICAL_RENAME_CERTIFIED = false
    fun mayRenameOriginal(): Boolean = PHYSICAL_RENAME_CERTIFIED
}

internal class MetadataSafRenameExecutionCoordinator(
    private val context: Context,
    private val sourceJournal: MetadataRecoveryJournalV1,
    private val renameJournal: MetadataSafRenameJournal,
) {
    enum class Refusal {
        UNCERTIFIED, INVALID_PREFLIGHT, OWNER_OR_RECEIPT_MISMATCH,
        RECOVERY_ALREADY_PENDING, JOURNAL_NOT_DURABLE,
    }
    sealed interface Result {
        data class Refused(val reason: Refusal) : Result
        /** Never announce success here; require full parent listing + SHA readback. */
        data class NeedsIndependentPostcheck(val transactionId: String) : Result
        /** Even null/exception can mean a provider performed the rename. */
        data class OutcomeUnknownRetainJournal(val transactionId: String) : Result
    }

    /**
     * Single gesture, no retry.
     *
     * This is the ONE documented exception to the journal's "never hold the exclusion
     * across provider IPC" contract (see `MetadataRecoveryJournalV1`): the source
     * journal has no durable rename reservation, so the only way to keep the provider
     * rename atomic against concurrent preparation/discard is to hold the
     * non-suspending critical section across the provider call. Releasing it between
     * PREPARED and INVOKING would let a concurrent preparation observe `Clear` while
     * the provider is renaming the same document. The block cannot suspend
     * (compile-time enforced), so no coroutine can interleave and cancellation cannot
     * tear the rename-journal transitions. INVOKING is persisted before the call and
     * every exception path retains the journal. The path is additionally gated by the
     * hard `false` [MetadataSafRenameCertification.PHYSICAL_RENAME_CERTIFIED].
     */
    suspend fun attempt(
        preflight: MetadataSafRenameSafetyPolicy.Input,
        intent: MetadataSafRenameRecoveryPolicy.Intent,
    ): Result = sourceJournal.exclusive {
        // Never enable on the basis of SAF's FLAG_SUPPORTS_RENAME alone.
        if (!MetadataSafRenameCertification.mayRenameOriginal())
            return@exclusive Result.Refused(Refusal.UNCERTIFIED)
        val gate = MetadataSafRenameSafetyPolicy.assess(preflight)
        if (gate !is MetadataSafRenameSafetyPolicy.Result.ReadyForProviderSpecificTrial)
            return@exclusive Result.Refused(Refusal.INVALID_PREFLIGHT)
        if (preflight.snapshot.target.sourceId != intent.sourceId ||
            preflight.snapshot.target.trackId != intent.trackId ||
            preflight.snapshot.target.uri != intent.beforeUri ||
            gate.sourceDocumentId != intent.beforeDocumentId ||
            preflight.proposal.before != intent.beforeName ||
            gate.proposedName != intent.requestedName ||
            gate.originalSha256 != intent.originalSha256 ||
            preflight.currentSourceBytes != intent.originalBytes)
            return@exclusive Result.Refused(Refusal.OWNER_OR_RECEIPT_MISMATCH)
        if (sourceJournal.inspect() != MetadataRecoveryJournalV1.Inspection.Clear ||
            renameJournal.inspect() != MetadataSafRenameJournal.State.Clear)
            return@exclusive Result.Refused(Refusal.RECOVERY_ALREADY_PENDING)

        val prepared = try { renameJournal.prepare(intent, mainJournalClear = true) }
            catch (_: Exception) { return@exclusive Result.Refused(Refusal.JOURNAL_NOT_DURABLE) }
        // Persist INVOKING before the potentially irreversible provider call.
        val invoking = try { renameJournal.markInvoking(prepared) }
            catch (_: Exception) { return@exclusive Result.Refused(Refusal.JOURNAL_NOT_DURABLE) }
        return@exclusive try {
            val returned = DocumentsContract.renameDocument(
                context.contentResolver, Uri.parse(intent.beforeUri), intent.requestedName)
            val recorded = renameJournal.markProviderReturned(invoking, returned?.toString())
            if (recorded.phase == MetadataSafRenameJournal.Phase.PROVIDER_RETURNED)
                Result.NeedsIndependentPostcheck(intent.transactionId)
            else Result.OutcomeUnknownRetainJournal(intent.transactionId)
        } catch (cancel: CancellationException) {
            try { renameJournal.markUncertain(invoking) } catch (_: Exception) { /* retain INVOKING */ }
            throw cancel
        } catch (_: Exception) {
            try { renameJournal.markUncertain(invoking) } catch (_: Exception) { /* retain INVOKING */ }
            Result.OutcomeUnknownRetainJournal(intent.transactionId)
        }
    }

    /**
     * ME-11P: only evidence collected from a fresh Android provider read can
     * advance the journal. The previous overload accepted a caller-fabricated
     * Observation and has intentionally been removed (no call sites existed).
     * This method is NOT connected to Compose and never updates Room.
     */
    suspend fun reconcileViaIndependentAndroidReader(
        request: MetadataSafRenameAndroidEvidenceReader.Request,
        ownerStillCurrentNow: () -> Boolean,
        playbackClosedNow: () -> Boolean,
    ): IndependentReadback {
        // The source-journal exclusion is brief here: read the durable intent and the
        // generation it is bound to, then release it for slow SAF I/O.
        val snapshot = sourceJournal.withExclusiveSnapshot {
            if (it != MetadataRecoveryJournalV1.Inspection.Clear) null
            else renameJournal.inspect() as? MetadataSafRenameJournal.State.Pending
        }
        val pending = snapshot.value
            ?: return IndependentReadback.Refused("RECOVERY_JOURNAL_UNAVAILABLE")
        if (pending.entry.phase !in setOf(
                MetadataSafRenameJournal.Phase.INVOKING,
                MetadataSafRenameJournal.Phase.PROVIDER_RETURNED,
                MetadataSafRenameJournal.Phase.UNKNOWN))
            return IndependentReadback.Refused("RENAME_PHASE_NOT_RECOVERABLE")
        if (!ownerStillCurrentNow() || !playbackClosedNow())
            return IndependentReadback.Refused("OWNER_OR_PLAYBACK_CHANGED")
        val collector = MetadataSafRenameAndroidEvidenceReader(context)
        val evidence = collector.observe(pending.entry.intent, request,
            pending.entry.returnedDocumentUri)
        if (evidence is MetadataSafRenameAndroidEvidenceReader.Result.Blocked)
            return IndependentReadback.Refused(evidence.reason.name)
        val observed = evidence as MetadataSafRenameAndroidEvidenceReader.Result.Observed
        // Main journal and rename intent must be the exact observation from before the
        // external read. A stale result is rejected rather than retried or overwritten.
        val publication = sourceJournal.publishIfUnchanged(snapshot.generation) {
            if (renameJournal.inspect() != pending ||
                !ownerStillCurrentNow() || !playbackClosedNow())
                IndependentReadback.Refused("EVIDENCE_NO_LONGER_CURRENT")
            else IndependentReadback.Classified(renameJournal.reconcile(observed.evidence))
        }
        return when (publication) {
            is MetadataRecoveryJournalV1.Publication.Stale ->
                IndependentReadback.Refused("EVIDENCE_NO_LONGER_CURRENT")
            is MetadataRecoveryJournalV1.Publication.Published -> publication.value
        }
    }

    sealed interface IndependentReadback {
        data class Refused(val reason: String) : IndependentReadback
        data class Classified(val verdict: MetadataSafRenameRecoveryPolicy.Verdict) : IndependentReadback
    }
}
