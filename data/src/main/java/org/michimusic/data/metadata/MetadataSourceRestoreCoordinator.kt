package org.michimusic.data.metadata

import android.app.PendingIntent
import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataCommitCertification
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataOpaquePreservationAudit
import org.michimusic.core.metadata.MetadataPhysicalOutcomePolicy
import org.michimusic.core.metadata.MetadataPreparedCommitSnapshot
import org.michimusic.core.metadata.MetadataProviderRecoveryProfile
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataRecoveryRestorePolicy
import org.michimusic.core.metadata.MetadataTarget

/**
 * ME-10R. Protected, opt-in source restoration blueprint using the SAME durable
 * journal as commit. Never called by UI; both physical source-write gates remain
 * hard-false. Restoration is only ever considered if live bytes are identical
 * to the staged candidate. UNKNOWN_BYTES is NOT safe to overwrite, since an
 * outside actor may have changed the provider document in the interim.
 *
 * Records ROLLBACK_PENDING BEFORE the first destructive provider open. All
 * errors or unverified readbacks retain both private copies. A successful byte
 * send alone is not a result or index publication. No automatic undo on start.
 */
internal class MetadataSourceRestoreCoordinator(context: Context) {
    data class Request(
        val transactionId: String,
        val target: MetadataTarget,
        val explicitlyConfirmedByUser: Boolean,
        val ownerStillCurrent: () -> Boolean,
        val playbackClosed: () -> Boolean,
        val providerProfile: MetadataProviderRecoveryProfile.Evidence,
    )
    enum class Refusal {
        ACTION_REQUIRED, NOT_CERTIFIED, JOURNAL_UNREADABLE,
        WRONG_TRANSACTION_OR_OWNER, NOT_IN_UNKNOWN_PHASE,
        BACKUP_OR_CANDIDATE_UNVERIFIED, INTENT_NOT_BOUND,
        SEMANTIC_OR_OPAQUE_AUDIT_FAILED, SOURCE_CHANGED_OR_UNREADABLE,
        PROVIDER_MISMATCH, PERMISSION_UNAVAILABLE,
        RESTORE_PRECONDITIONS_FAILED, PROTECTED_JOURNAL_TRANSITION,
    }
    sealed interface Outcome {
        data class Blocked(val reason: Refusal) : Outcome
        data class PermissionRequired(val intent: PendingIntent) : Outcome
        /** No restoration happened: original fingerprint was already present. */
        data class OriginalWasIntact(val transactionId: String) : Outcome
        /** Verified immediate readback, but no automatic cleanup or publication. */
        data class OriginalBytesReadBack(val transactionId: String) : Outcome
        data class KeepBothPrivateCopies(val transactionId: String,
            val observed: MetadataPhysicalOutcomePolicy.State) : Outcome
    }
    private val journal = MetadataRecoveryJournalV1(File(context.noBackupFilesDir,
        "michi-metadata-editor-v1"))
    private val intents = MetadataCommitIntentStore(journal)
    private val currentBytes = MetadataCurrentBytesReader(context)
    private val permission = MetadataExactWritePermission(context)
    private val transport = MetadataProviderMutationTransport(context)
    private val reader = MetadataProviderReadAdapter(context)

    private sealed interface Reserved {
        data class Refused(val outcome: Outcome) : Reserved
        data class Advanced(val entry: MetadataRecoveryJournalV1.Entry) : Reserved
    }

    suspend fun attempt(request: Request): Outcome = withContext(Dispatchers.IO) {
        if (!request.explicitlyConfirmedByUser)
            return@withContext Outcome.Blocked(Refusal.ACTION_REQUIRED)
        // The two independent production gates are deliberate and false here.
        if (!MetadataCommitCertification.mayMutateOriginal() ||
            !MetadataProviderRecoveryProfile.permitsSourceMutation(request.providerProfile))
            return@withContext Outcome.Blocked(Refusal.NOT_CERTIFIED)
        val job = currentCoroutineContext()[Job]
        try {
            lateinit var entry: MetadataRecoveryJournalV1.Entry
            lateinit var intent: MetadataCommitIntentManifest.Receipt
            lateinit var evidence: MetadataPreparedCommitSnapshot.Evidence
            // Phase 1: brief exclusion. Durable identity and private receipt only; no
            // provider read and no full-file rehash runs under the lock.
            val snapshot = journal.withExclusiveSnapshot { inspection ->
                val pending = (inspection as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
                    ?: return@withExclusiveSnapshot Outcome.Blocked(Refusal.JOURNAL_UNREADABLE)
                if (pending.id != request.transactionId ||
                    pending.sourceId != request.target.sourceId ||
                    pending.trackId != request.target.trackId ||
                    pending.uri != request.target.uri)
                    return@withExclusiveSnapshot Outcome.Blocked(Refusal.WRONG_TRANSACTION_OR_OWNER)
                if (pending.phase != MetadataRecoveryPhase.MUTATION_UNKNOWN)
                    return@withExclusiveSnapshot Outcome.Blocked(Refusal.NOT_IN_UNKNOWN_PHASE)
                if (!request.ownerStillCurrent() || !request.playbackClosed())
                    return@withExclusiveSnapshot Outcome.Blocked(Refusal.SOURCE_CHANGED_OR_UNREADABLE)
                val prepared = MetadataPreparedCommitSnapshot.evaluate(
                    MetadataPreparedCommitSnapshot.Input(pending.id,MetadataRecoveryPhase.STAGE_READY,
                        pending.sourceId,pending.trackId,pending.uri,pending.expectedBytes,
                        pending.originalSha256,pending.stageBytes,pending.stageSha256,pending.format))
                if (prepared !is MetadataPreparedCommitSnapshot.Decision.Candidate)
                    return@withExclusiveSnapshot Outcome.Blocked(Refusal.BACKUP_OR_CANDIDATE_UNVERIFIED)
                val receipt = (intents.inspect(pending) as? MetadataCommitIntentStore.Inspection.Present)
                    ?.receipt ?: return@withExclusiveSnapshot Outcome.Blocked(Refusal.INTENT_NOT_BOUND)
                entry=pending; intent=receipt; evidence=prepared.evidence
                null
            }
            snapshot.value?.let { return@withContext it }
            job?.ensureActive()

            // Phase 2: unlocked verification. Provider reads, full-file audits and
            // permission inspection never run while the journal lock is held.
            if (!journal.privateArtifactsIntact(entry))
                return@withContext Outcome.Blocked(Refusal.BACKUP_OR_CANDIDATE_UNVERIFIED)
            val observed = currentBytes.read(entry.sourceId,entry.uri,
                maxOf(entry.expectedBytes,entry.stageBytes)) { job?.ensureActive() }
            if (observed !is MetadataCurrentBytesReader.Result.Read)
                return@withContext Outcome.Blocked(Refusal.SOURCE_CHANGED_OR_UNREADABLE)
            val physical = MetadataPhysicalOutcomePolicy.classify(
                MetadataPhysicalOutcomePolicy.Expected(entry.expectedBytes,
                    entry.originalSha256,entry.stageBytes,entry.stageSha256),
                observed.fingerprint)
            if (physical == MetadataPhysicalOutcomePolicy.State.ORIGINAL_INTACT) {
                // Withhold the "already intact" verdict if the record moved meanwhile.
                if (!journal.isUnchanged(snapshot.generation,entry))
                    return@withContext Outcome.Blocked(Refusal.WRONG_TRANSACTION_OR_OWNER)
                return@withContext Outcome.OriginalWasIntact(entry.id)
            }
            if (physical != MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED)
                return@withContext Outcome.Blocked(Refusal.SOURCE_CHANGED_OR_UNREADABLE)
            val exactSource = try {
                reader.openForRead(entry.sourceId,entry.uri,entry.stageBytes)
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) {
                return@withContext Outcome.Blocked(Refusal.SOURCE_CHANGED_OR_UNREADABLE)
            }
            // Revalidate exact candidate bytes and provider stamp before
            // inspecting grants or entering the protected rollback phase.
            if (!reader.verifyBytes(exactSource,entry.stageBytes,entry.stageSha256) {
                    job?.ensureActive()
                }) return@withContext Outcome.Blocked(Refusal.SOURCE_CHANGED_OR_UNREADABLE)
            val providerMatches = when (exactSource.provider) {
                MetadataDocumentProvider.MEDIASTORE -> request.providerProfile.provider ==
                    MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO
                MetadataDocumentProvider.SAF -> request.providerProfile.provider ==
                    MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT
                else -> false
            }
            if (!providerMatches)
                return@withContext Outcome.Blocked(Refusal.PROVIDER_MISMATCH)
            if (MetadataCommitIntentManifest.verify(intent,entry.id,request.target,
                    exactSource.provider,evidence.format,entry.expectedBytes,
                    entry.originalSha256,entry.stageBytes,entry.stageSha256) !=
                MetadataCommitIntentManifest.Verdict.BoundForExternalReview)
                return@withContext Outcome.Blocked(Refusal.INTENT_NOT_BOUND)
            if (MetadataPreparedSemanticInspector.inspectFiles(journal.backup(entry),
                    journal.stage(entry),intent){ job?.ensureActive() }
                !is MetadataPreparedSemanticInspector.Inspection.Compared ||
                MetadataOpaquePreservationAudit.compare(journal.backup(entry),
                    journal.stage(entry),intent.container,
                    intent.changes.mapTo(linkedSetOf()){it.field})
                !is MetadataOpaquePreservationAudit.Verdict.Compared)
                return@withContext Outcome.Blocked(Refusal.SEMANTIC_OR_OPAQUE_AUDIT_FAILED)
            val grant = when (val check=permission.inspect(entry.sourceId,entry.uri,true)) {
                is MetadataExactWritePermission.Result.MediaStoreConsentRequired ->
                    return@withContext Outcome.PermissionRequired(check.intent)
                MetadataExactWritePermission.Result.ExistingExactGrant -> true
                else -> false
            }

            // Phase 3: durable ROLLBACK_PENDING reservation persisted BEFORE the first
            // destructive provider open. `advance` re-hashes the private artifacts
            // inside the exclusion, so a stale observation is refused here.
            val reservation = journal.publishIfUnchanged(snapshot.generation,entry) {
                if (!request.ownerStillCurrent() || !request.playbackClosed())
                    return@publishIfUnchanged Reserved.Refused(
                        Outcome.Blocked(Refusal.SOURCE_CHANGED_OR_UNREADABLE))
                val readiness = MetadataRecoveryRestorePolicy.evaluate(
                    MetadataRecoveryRestorePolicy.Input(
                        request.explicitlyConfirmedByUser,entry.phase,request.ownerStillCurrent(),
                        request.playbackClosed(),journal.privateArtifactsIntact(entry),physical,
                        exactSource.provider,grant,
                        MetadataProviderRecoveryProfile.permitsSourceMutation(request.providerProfile),
                        MetadataCommitCertification.mayMutateOriginal()))
                if (readiness != MetadataRecoveryRestorePolicy.Decision.ReadyForTransportBehindCertification)
                    return@publishIfUnchanged Reserved.Refused(
                        Outcome.Blocked(Refusal.RESTORE_PRECONDITIONS_FAILED))
                val restoring = entry.copy(phase=MetadataRecoveryPhase.ROLLBACK_PENDING)
                journal.advance(entry,restoring) // persistent before touching provider
                Reserved.Advanced(restoring)
            }
            val (restoring, reservedGeneration) = when (reservation) {
                is MetadataRecoveryJournalV1.Publication.Stale ->
                    return@withContext Outcome.Blocked(Refusal.PROTECTED_JOURNAL_TRANSITION)
                is MetadataRecoveryJournalV1.Publication.Published ->
                    when (val value = reservation.value) {
                        is Reserved.Refused -> return@withContext value.outcome
                        is Reserved.Advanced -> value.entry to reservation.generation
                    }
            }

            // Phase 4: unlocked physical transport and cold readback.
            val transferred = transport.restore(restoring,journal.backup(entry),
                request.providerProfile) { job?.ensureActive() }
            val newSource = if (transferred ==
                MetadataProviderMutationTransport.Result.BytesSentRequireColdReadback)
                currentBytes.read(entry.sourceId,entry.uri,
                    maxOf(entry.expectedBytes,entry.stageBytes)){ job?.ensureActive() }
            else null
            val post = if (newSource is MetadataCurrentBytesReader.Result.Read)
                MetadataPhysicalOutcomePolicy.classify(
                    MetadataPhysicalOutcomePolicy.Expected(entry.expectedBytes,
                        entry.originalSha256,entry.stageBytes,entry.stageSha256),
                    newSource.fingerprint)
                else MetadataPhysicalOutcomePolicy.State.READBACK_UNAVAILABLE
            if (post == MetadataPhysicalOutcomePolicy.State.ORIGINAL_INTACT) {
                // Phase 5: publish RESTORED_VERIFIED only while the reservation stands.
                val advanced=journal.publishIfUnchanged(reservedGeneration,restoring) {
                    journal.advance(restoring,
                        restoring.copy(phase=MetadataRecoveryPhase.RESTORED_VERIFIED))
                    true
                }
                if (advanced !is MetadataRecoveryJournalV1.Publication.Published ||
                    advanced.value != true)
                    return@withContext Outcome.KeepBothPrivateCopies(entry.id,post)
                return@withContext Outcome.OriginalBytesReadBack(entry.id)
            }
            // ROLLBACK_PENDING and both private copies remain durable.
            Outcome.KeepBothPrivateCopies(entry.id,post)
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) {
            Outcome.Blocked(Refusal.PROTECTED_JOURNAL_TRANSITION)
        }
    }
}
