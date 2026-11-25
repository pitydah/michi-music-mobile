package org.michimusic.data.metadata

import android.app.PendingIntent
import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataRecoveryActionPolicy
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataRecoveryPhasePolicy
import org.michimusic.core.metadata.MetadataTarget

/**
 * ME-07: manually invoked, exact-track recovery console. Its only destructive
 * capability deletes PRIVATE prepared artifacts before any source mutation.
 * It NEVER restores, writes, truncates or renames an audio provider document.
 * No background inspection and no release of any possibly unique backup.
 */
class MetadataRecoveryConsole(
    context: Context,
    private val database: org.michimusic.data.cache.MichiDatabase? = null,
) {
    private val context = context.applicationContext
    private val journal=MetadataRecoveryJournalV1(
        File(context.noBackupFilesDir,"michi-metadata-editor-v1"))
    private val intents=MetadataCommitIntentStore(journal)

    sealed interface Status {
        data object NoPending : Status
        data object OtherTrackHasPendingWork : Status
        data class DiscardablePrivateStage(val transactionId: String,
            val phase: MetadataRecoveryPhase) : Status
        data class ProtectedKeepBackup(
            val phase: MetadataRecoveryPhase?,
            val transactionId: String = "",
        ) : Status
        data object CorruptOrInaccessibleKeepBackup : Status
        data object ActionMustBeExplicit : Status
    }
    sealed interface RestoreResult {
        data object RestoredVerified : RestoreResult
        data object OriginalWasIntact : RestoreResult
        data object CandidateInstalledFinalizeRequired : RestoreResult
        data class PermissionRequired(val intent: PendingIntent) : RestoreResult
        data class KeptPrivateCopies(val observed: String) : RestoreResult
        data class Blocked(val reason: String) : RestoreResult
        data object ActionRequired : RestoreResult
        data object NoExactPendingRestore : RestoreResult
    }
    private data class RestoreBinding(
        val entry: MetadataRecoveryJournalV1.Entry,
        val permit: MetadataRestorePermit,
    )
    enum class DiscardResult {
        DISCARDED_PRIVATE_ONLY,
        NO_EXPLICIT_CONFIRMATION,
        NOT_EXACT_TRACK_OR_CHANGED,
        MUTATION_MAY_HAVE_OCCURRED,
        CORRUPT_OR_UNREADABLE,
        PRIVATE_CLEANUP_INCOMPLETE,
    }

    private fun sameTrack(entry: MetadataRecoveryJournalV1.Entry,
        sourceId: String, trackId: String, uri: String): Boolean =
        entry.sourceId == sourceId && entry.trackId == trackId && entry.uri == uri &&
            trackId.isNotBlank() && uri.startsWith("content://")

    /** A manual click is mandatory. Only a private phase is called discardable. */
    suspend fun inspect(sourceId: String, trackId: String, uri: String,
        explicitUserAction: Boolean): Status = withContext(Dispatchers.IO) {
        if (!explicitUserAction) return@withContext Status.ActionMustBeExplicit
        try {
            lateinit var entry: MetadataRecoveryJournalV1.Entry
            val observation = journal.withExclusiveSnapshot { state ->
                when(state) {
                    MetadataRecoveryJournalV1.Inspection.Clear -> Status.NoPending
                    is MetadataRecoveryJournalV1.Inspection.Blocked -> Status.CorruptOrInaccessibleKeepBackup
                    is MetadataRecoveryJournalV1.Inspection.Pending -> {
                        val candidate=state.entry
                        if (!sameTrack(candidate,sourceId,trackId,uri)) Status.OtherTrackHasPendingWork
                        else if (journal.privateTrialPresent(candidate))
                            Status.ProtectedKeepBackup(candidate.phase, candidate.id)
                        else { entry=candidate; null }
                    }
                }
            }
            observation.value?.let { return@withContext it }
            // Full private rehash runs WITHOUT the journal lock. The verdict is
            // withheld if the durable record changed while it was computed.
            val intact=journal.privateArtifactsIntact(entry)
            val decision=MetadataRecoveryActionPolicy.inspect(
                journalReadable=true,hasPending=true,targetMatches=true,
                privateArtifactsIntact=intact,phase=entry.phase,
                // Only checks selected tuple, NOT a physical owner/stamp proof.
                targetStillCurrent=true)
            val status = if (decision.action == MetadataRecoveryActionPolicy.Action.PRIVATE_PREPARATION_CAN_BE_DISCARDED)
                Status.DiscardablePrivateStage(entry.id,entry.phase)
            else Status.ProtectedKeepBackup(entry.phase, entry.id)
            if (!journal.isUnchanged(observation.generation,entry))
                return@withContext Status.CorruptOrInaccessibleKeepBackup
            status
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { Status.CorruptOrInaccessibleKeepBackup }
    }

    /**
     * F4: explicit finish/reconcile of an already started transaction. It uses
     * the single draft-free coordinator and never writes source bytes again.
     */
    suspend fun resumeExact(
        transactionId: String,
        sourceId: String,
        trackId: String,
        uri: String,
        explicitlyConfirmed: Boolean,
        ownerStillCurrent: () -> Boolean,
        playbackClosed: () -> Boolean,
    ): MetadataRecoveryCoordinator.Outcome {
        if (!explicitlyConfirmed)
            return MetadataRecoveryCoordinator.Outcome.Blocked("ACTION_REQUIRED")
        val db = database
            ?: return MetadataRecoveryCoordinator.Outcome.Blocked("DATABASE_UNAVAILABLE")
        return MetadataSaveLocks.tryRun(
            onBusy = { MetadataRecoveryCoordinator.Outcome.Blocked("IN_PROGRESS") },
        ) {
            withContext(Dispatchers.IO) {
                MetadataRecoveryCoordinator(context, db).resume(
                    MetadataRecoveryCoordinator.RecoveryRequest(
                        transactionId = transactionId, sourceId = sourceId,
                        trackId = trackId, uri = uri, userRequested = true,
                        ownerStillCurrent = ownerStillCurrent,
                        playbackClosed = playbackClosed,
                    ),
                )
            }
        }
    }

    /**
     * ME-13 S5: explicit, typed restore of the original bytes for the exact
     * local MediaStore MP3. The permit is minted ONLY from the durable journal
     * token + intent owner digest, so a process restart can still authorize the
     * exact transaction without trusting a Compose draft.
     */
    suspend fun restoreOriginal(
        sourceId: String, trackId: String, uri: String,
        explicitlyConfirmed: Boolean,
        ownerStillCurrent: () -> Boolean,
        playbackClosed: () -> Boolean,
    ): RestoreResult = withContext(Dispatchers.IO) {
        if (!explicitlyConfirmed) return@withContext RestoreResult.ActionRequired
        try {
            val bound = journal.withExclusiveSnapshot { state ->
                val pending = (state as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
                    ?: return@withExclusiveSnapshot RestoreResult.NoExactPendingRestore
                if (!sameTrack(pending, sourceId, trackId, uri))
                    return@withExclusiveSnapshot RestoreResult.NoExactPendingRestore
                if (pending.phase !in setOf(MetadataRecoveryPhase.MUTATING,
                        MetadataRecoveryPhase.MUTATION_UNKNOWN))
                    return@withExclusiveSnapshot RestoreResult.Blocked("PHASE_NOT_RECOVERABLE")
                if (!journal.privateArtifactsIntact(pending))
                    return@withExclusiveSnapshot RestoreResult.Blocked("PRIVATE_COPIES_INVALID")
                val intent = (intents.inspect(pending) as?
                    MetadataCommitIntentStore.Inspection.Present)?.receipt
                    ?: return@withExclusiveSnapshot RestoreResult.Blocked("INTENT_NOT_DURABLE")
                val permit = MetadataRestorePermit.fromDurable(
                    pending, intent, explicitUserAction = true,
                ) ?: return@withExclusiveSnapshot RestoreResult.Blocked("PERMIT_UNAVAILABLE")
                RestoreBinding(pending, permit)
            }
            val value = bound.value
            if (value is RestoreResult) return@withContext value
            val binding = value as? RestoreBinding
                ?: return@withContext RestoreResult.Blocked("JOURNAL_SNAPSHOT_INVALID")
            val entry = binding.entry
            val permit = binding.permit
            val outcome = MetadataSourceRestoreCoordinator(context).attempt(
                MetadataSourceRestoreCoordinator.Request(
                    transactionId = entry.id,
                    target = MetadataTarget(entry.sourceId, entry.trackId,
                        entry.uri, entry.ownerEvidenceToken),
                    explicitlyConfirmedByUser = true,
                    ownerStillCurrent = ownerStillCurrent,
                    playbackClosed = playbackClosed,
                    permit = permit,
                ),
            )
            when (outcome) {
                is MetadataSourceRestoreCoordinator.Outcome.RestoredVerified ->
                    RestoreResult.RestoredVerified
                is MetadataSourceRestoreCoordinator.Outcome.OriginalWasIntact ->
                    RestoreResult.OriginalWasIntact
                is MetadataSourceRestoreCoordinator.Outcome.CandidateInstalledFinalizeRequired ->
                    RestoreResult.CandidateInstalledFinalizeRequired
                is MetadataSourceRestoreCoordinator.Outcome.PermissionRequired ->
                    RestoreResult.PermissionRequired(outcome.intent)
                is MetadataSourceRestoreCoordinator.Outcome.KeepBothPrivateCopies ->
                    RestoreResult.KeptPrivateCopies(outcome.observed.name)
                is MetadataSourceRestoreCoordinator.Outcome.Blocked ->
                    RestoreResult.Blocked(outcome.reason.name)
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { RestoreResult.Blocked("RESTORE_UNAVAILABLE") }
    }

    /**
     * Separate confirmation click + exact ID+owner+URI compare at publication time.
     * If either private delete fails, journal stays pending; never claim success.
     */
    suspend fun discardPrivatePreparation(
        sourceId: String, trackId: String, uri: String,
        transactionId: String, explicitlyConfirmed: Boolean,
    ): DiscardResult = withContext(Dispatchers.IO) {
        if (!explicitlyConfirmed) return@withContext DiscardResult.NO_EXPLICIT_CONFIRMATION
        try {
            lateinit var entry: MetadataRecoveryJournalV1.Entry
            val observation = journal.withExclusiveSnapshot { state ->
                val pending=(state as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
                    ?: return@withExclusiveSnapshot DiscardResult.CORRUPT_OR_UNREADABLE
                if (pending.id!=transactionId || !sameTrack(pending,sourceId,trackId,uri))
                    return@withExclusiveSnapshot DiscardResult.NOT_EXACT_TRACK_OR_CHANGED
                if (!MetadataRecoveryPhasePolicy.canDiscardPrivateOnly(pending.phase))
                    return@withExclusiveSnapshot DiscardResult.MUTATION_MAY_HAVE_OCCURRED
                entry=pending
                null
            }
            observation.value?.let { return@withContext it }
            if (!journal.privateArtifactsIntact(entry))
                return@withContext DiscardResult.CORRUPT_OR_UNREADABLE
            // The delete is published only while the record is still the observed one.
            val outcome=journal.publishIfUnchanged(observation.generation,entry) {
                journal.discardPrepared(entry.id)
            }
            when (outcome) {
                is MetadataRecoveryJournalV1.Publication.Stale ->
                    DiscardResult.NOT_EXACT_TRACK_OR_CHANGED
                is MetadataRecoveryJournalV1.Publication.Published ->
                    if (outcome.value) DiscardResult.DISCARDED_PRIVATE_ONLY
                    else DiscardResult.PRIVATE_CLEANUP_INCOMPLETE
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { DiscardResult.CORRUPT_OR_UNREADABLE }
    }
}
