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
import org.michimusic.core.metadata.MetadataWriteProfileRegistry

/**
 * ME-10R / ME-13 S5. Protected source restoration using the SAME durable
 * journal as commit. Two routes exist:
 *
 * - The legacy global route remains hard-false behind
 *   [MetadataCommitCertification] and [MetadataProviderRecoveryProfile].
 * - The S5 route accepts only an exact, transaction-bound
 *   [MetadataRestorePermit] for the provider/container profile recorded by the
 *   durable transaction.
 *
 * Restoration is only ever considered after the exact private backup and the
 * durable manifest are revalidated. A verified candidate means the write landed:
 * the caller must finalize it, never restore. ORIGINAL_INTACT is reported without
 * touching the provider. UNKNOWN/truncated bytes require the explicit permit and
 * a durable ROLLBACK_PENDING reservation BEFORE the first destructive open.
 * Records ROLLBACK_PENDING before the first destructive provider open. All
 * errors or unverified readbacks retain both private copies. A successful byte
 * send alone is not a result or index publication. No automatic undo on start.
 */
internal class MetadataSourceRestoreCoordinator(
    context: Context,
    private val permissionProbe: ((String, String, Boolean) ->
        MetadataExactWritePermission.Result)? = null,
) {
    data class Request(
        val transactionId: String,
        val target: MetadataTarget,
        val explicitlyConfirmedByUser: Boolean,
        val ownerStillCurrent: () -> Boolean,
        val playbackClosed: () -> Boolean,
        val providerProfile: MetadataProviderRecoveryProfile.Evidence? = null,
        val permit: MetadataRestorePermit? = null,
    )
    enum class Refusal {
        ACTION_REQUIRED, NOT_CERTIFIED, JOURNAL_UNREADABLE,
        WRONG_TRANSACTION_OR_OWNER, NOT_IN_UNKNOWN_PHASE, PERMIT_MISMATCH,
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
        /** The write already landed. Finalize/index it; do not restore. */
        data class CandidateInstalledFinalizeRequired(val transactionId: String) : Outcome
        /** Only this outcome means a provider reread proved the original bytes. */
        data class RestoredVerified(val transactionId: String) : Outcome
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
        val scoped = request.permit?.authorizes(request.transactionId, request.target) == true
        val profile = request.providerProfile
        if (!scoped) {
            // The two independent production gates are deliberate and false here.
            if (!MetadataCommitCertification.mayMutateOriginal() ||
                profile == null ||
                !MetadataProviderRecoveryProfile.permitsSourceMutation(profile))
                return@withContext Outcome.Blocked(Refusal.NOT_CERTIFIED)
        }
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
                if (request.permit != null && !request.permit.matches(pending))
                    return@withExclusiveSnapshot Outcome.Blocked(Refusal.PERMIT_MISMATCH)
                if (pending.phase !in setOf(MetadataRecoveryPhase.MUTATING,
                        MetadataRecoveryPhase.MUTATION_UNKNOWN))
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
                if (!scoped) return@withContext Outcome.OriginalWasIntact(entry.id)
                // T45: an already-restored source needs no provider write, but the
                // typed transaction must still reach its idempotent terminal state.
                val terminal = journal.publishIfUnchanged(snapshot.generation,entry) {
                    val unknown = if (entry.phase == MetadataRecoveryPhase.MUTATING)
                        entry.copy(phase=MetadataRecoveryPhase.MUTATION_UNKNOWN) else entry
                    if (unknown !== entry) journal.advance(entry,unknown)
                    journal.advance(unknown,
                        unknown.copy(phase=MetadataRecoveryPhase.RESTORED_VERIFIED))
                    true
                }
                return@withContext when (terminal) {
                    is MetadataRecoveryJournalV1.Publication.Stale ->
                        Outcome.Blocked(Refusal.PROTECTED_JOURNAL_TRANSITION)
                    is MetadataRecoveryJournalV1.Publication.Published ->
                        if (terminal.value) Outcome.RestoredVerified(entry.id)
                        else Outcome.KeepBothPrivateCopies(entry.id,physical)
                }
            }
            if (physical == MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED)
                return@withContext Outcome.CandidateInstalledFinalizeRequired(entry.id)
            if (physical != MetadataPhysicalOutcomePolicy.State.UNKNOWN_BYTES)
                return@withContext Outcome.Blocked(Refusal.SOURCE_CHANGED_OR_UNREADABLE)
            // Truncated/unknown bytes are eligible only through the exact typed
            // permit. The provider is reopened independently against the stamp
            // just observed; the stale candidate is not required to be installed.
            val exactSource = try {
                reader.openForRead(entry.sourceId,entry.uri,observed.fingerprint.bytes)
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) {
                return@withContext Outcome.Blocked(Refusal.SOURCE_CHANGED_OR_UNREADABLE)
            }
            val providerMatches = when (exactSource.provider) {
                MetadataDocumentProvider.MEDIASTORE ->
                    if (scoped) MetadataWriteProfileRegistry
                        .byId(request.permit?.profileId.orEmpty())?.provider ==
                        MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO
                    else profile?.provider == MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO
                MetadataDocumentProvider.SAF ->
                    if (scoped) MetadataWriteProfileRegistry
                        .byId(request.permit?.profileId.orEmpty())?.provider ==
                        MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT
                    else profile?.provider ==
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
            val semantic = MetadataPreparedSemanticInspector.inspectFiles(
                journal.backup(entry), journal.stage(entry), intent,
            ) { job?.ensureActive() }
            if (semantic !is MetadataPreparedSemanticInspector.Inspection.Compared)
                return@withContext Outcome.Blocked(Refusal.SEMANTIC_OR_OPAQUE_AUDIT_FAILED)
            // MP4 has no byte-stable moov layout after covr surgery: moov is
            // relocated and stco/co64 are patched. Preserve the provider-neutral
            // audio/sample evidence instead; the prepared semantic inspector
            // above already verifies every selected and unselected resource.
            val opaqueOk = if (intent.container ==
                org.michimusic.core.metadata.MetadataAudioContainer.MP4_M4A) {
                m4aAudioPreserved(
                    journal.backup(entry),
                    journal.stage(entry),
                ) { job?.ensureActive() }
            } else {
                (MetadataOpaquePreservationAudit.compare(journal.backup(entry),
                    journal.stage(entry),intent.container,
                    intent.changes.mapTo(linkedSetOf()){it.field}, intent.resources)
                    is MetadataOpaquePreservationAudit.Verdict.Compared)
            }
            if (!opaqueOk)
                return@withContext Outcome.Blocked(Refusal.SEMANTIC_OR_OPAQUE_AUDIT_FAILED)
            val grant = when (val check = inspectPermission(entry)) {
                is MetadataExactWritePermission.Result.MediaStoreConsentRequired ->
                    return@withContext Outcome.PermissionRequired(check.intent)
                MetadataExactWritePermission.Result.ExistingExactGrant -> true
                else -> false
            }
            // Cancellation at the consent/permission boundary must win before
            // the durable rollback reservation and any destructive provider open.
            job?.ensureActive()

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
                        profile?.let {
                            MetadataProviderRecoveryProfile.permitsSourceMutation(it)
                        } == true,
                        MetadataCommitCertification.mayMutateOriginal(),
                        scopedRestorePermit = scoped))
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

            // Phase 4: unlocked physical transport and independent cold readback.
            val transferred = if (scoped) {
                transport.restoreWithPermit(restoring,journal.backup(entry),request.permit!!)
            } else {
                val legacyProfile = profile
                    ?: return@withContext Outcome.Blocked(Refusal.PROVIDER_MISMATCH)
                transport.restore(restoring,journal.backup(entry),legacyProfile)
            }
            val newSource = if (transferred ==
                MetadataProviderMutationTransport.Result.BytesSentRequireColdReadback)
                when (val read = currentBytes.readCommittedCandidate(
                    entry.sourceId, entry.uri, entry.expectedBytes, entry.originalSha256,
                ) { job?.ensureActive() }) {
                    is MetadataCurrentBytesReader.PostCommitRead.Exact ->
                        MetadataCurrentBytesReader.Result.Read(
                            MetadataPhysicalOutcomePolicy.Observed(
                                read.actualSize, read.actualSha256))
                    is MetadataCurrentBytesReader.PostCommitRead.Rejected -> null
                }
            else null
            val post = if (newSource is MetadataCurrentBytesReader.Result.Read)
                MetadataPhysicalOutcomePolicy.classify(
                    MetadataPhysicalOutcomePolicy.Expected(entry.expectedBytes,
                        entry.originalSha256,entry.stageBytes,entry.stageSha256),
                    newSource.fingerprint)
                else MetadataPhysicalOutcomePolicy.State.READBACK_UNAVAILABLE
            if (post == MetadataPhysicalOutcomePolicy.State.ORIGINAL_INTACT &&
                newSource is MetadataCurrentBytesReader.Result.Read &&
                newSource.fingerprint.bytes == entry.expectedBytes &&
                newSource.fingerprint.sha256 == entry.originalSha256) {
                // Phase 5: publish RESTORED_VERIFIED only while the reservation stands.
                val advanced=journal.publishIfUnchanged(reservedGeneration,restoring) {
                    journal.advance(restoring,
                        restoring.copy(phase=MetadataRecoveryPhase.RESTORED_VERIFIED))
                    true
                }
                if (advanced !is MetadataRecoveryJournalV1.Publication.Published ||
                    advanced.value != true)
                    return@withContext Outcome.KeepBothPrivateCopies(entry.id,post)
                return@withContext Outcome.RestoredVerified(entry.id)
            }
            // ROLLBACK_PENDING and both private copies remain durable.
            Outcome.KeepBothPrivateCopies(entry.id,post)
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) {
            Outcome.Blocked(Refusal.PROTECTED_JOURNAL_TRANSITION)
        }
    }

    private fun m4aAudioPreserved(
        before: File,
        after: File,
        checkCancellation: () -> Unit = {},
    ): Boolean = try {
        fun evidence(file: File) = java.io.FileInputStream(file).use { input ->
            (MetadataM4aAudioOnlyInspector.inspect(input.channel, checkCancellation)
                as? MetadataM4aAudioOnlyInspector.Result.AudioOnly)?.evidence
        }
        val left = evidence(before)
        val right = evidence(after)
        left != null && right != null &&
            MetadataM4aAudioOnlyInspector.equivalent(left, right)
    } catch (cancel: CancellationException) { throw cancel
    } catch (_: Exception) { false }

    private fun inspectPermission(
        entry: MetadataRecoveryJournalV1.Entry,
    ): MetadataExactWritePermission.Result =
        permissionProbe?.invoke(entry.sourceId, entry.uri, true)
            ?: permission.inspect(entry.sourceId, entry.uri, true)
}
