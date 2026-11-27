package org.michimusic.data.metadata

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataCommitCertification
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataPhysicalOutcomePolicy
import org.michimusic.core.metadata.MetadataReadAccessPolicy
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.core.metadata.MetadataVerifiedReceiptGate
import org.michimusic.data.metadata.MetadataRecoveryJournalV1.Publication

/**
 * ME-11C: independently reopens the exact source following a historical
 * MUTATION_UNKNOWN. This is the missing postcheck -> journal transition.
 * No UI invokes it; it never writes source, deletes backups, publishes Room,
 * or elevates a private preview to a verified physical-write receipt.
 * Every path stays denied while source writer certification is false.
 *
 * ME-11R: the journal is observed under a brief exclusion, the cold readback and the
 * semantic source inspection run WITHOUT that lock, and the POSTCHECK_VERIFIED
 * promotion re-enters it only after re-proving ownership and private evidence. A
 * journal that moved during the read is reported as changed, never promoted.
 */
internal class MetadataPostMutationFinalizationCoordinator(context: Context) {
    private val journal=MetadataRecoveryJournalV1(File(context.noBackupFilesDir,
        "michi-metadata-editor-v1"))
    private val intents=MetadataCommitIntentStore(journal)
    private val read=MetadataCurrentBytesReader(context)
    private val semantic=MetadataPostMutationSourceInspector(context)

    enum class Refusal { ACTION_REQUIRED, TARGET_CHANGED, JOURNAL_NOT_PENDING,
        NOT_A_POST_MUTATION, INTENT_MISSING, INVALID_PRIVATE_EVIDENCE,
        SOURCE_NOT_CANDIDATE, SOURCE_SEMANTICS_UNVERIFIED, JOURNAL_CHANGED,
        WRITER_NOT_CERTIFIED }
    internal enum class FinalizationStep {
        SNAPSHOT, INTENT_BINDING, PRIVATE_ARTIFACTS, SOURCE_READ,
        SOURCE_HASH, SOURCE_SEMANTICS, JOURNAL_GENERATION, PERMIT, RECEIPT_GATE,
    }
    sealed interface Result {
        data class Blocked(
            val reason: Refusal,
            val step: FinalizationStep = FinalizationStep.SNAPSHOT,
            val code: String = reason.name,
        ) : Result
        /** Diagnostic only; preserving the backup is mandatory. */
        data class ComparedButNotCertified(val transactionId: String) : Result
        /** Only minted by MetadataVerifiedReceiptGate, never from caller booleans. */
        data class PreparedForExternalCas(val receipt:
            MetadataVerifiedReceiptGate.Decision.ReadyForIndexCompare) : Result
    }

    suspend fun confirm(
        transactionId:String,target:MetadataTarget,explicitUserAction:Boolean,
        ownerCurrent:()->Boolean,playbackClosed:()->Boolean,
        permit: MetadataWritePermit? = null,
    ):Result = withContext(Dispatchers.IO) {
        if (!explicitUserAction) return@withContext Result.Blocked(Refusal.ACTION_REQUIRED)
        val job=currentCoroutineContext()[Job]
        try {
            lateinit var pending:MetadataRecoveryJournalV1.Entry
            lateinit var intent:MetadataCommitIntentManifest.Receipt
            // Phase 1: local evidence under a brief exclusion.
            val snapshot=journal.withExclusiveSnapshot { inspection ->
                val entry=(inspection as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
                    ?: return@withExclusiveSnapshot Result.Blocked(
                        Refusal.JOURNAL_NOT_PENDING, FinalizationStep.SNAPSHOT)
                if (entry.id != transactionId || entry.sourceId != target.sourceId ||
                    entry.trackId != target.trackId || entry.uri != target.uri ||
                    !ownerCurrent() || !playbackClosed())
                    return@withExclusiveSnapshot Result.Blocked(
                        Refusal.TARGET_CHANGED, FinalizationStep.INTENT_BINDING)
                if (entry.phase != MetadataRecoveryPhase.MUTATING &&
                    entry.phase != MetadataRecoveryPhase.MUTATION_UNKNOWN &&
                    entry.phase != MetadataRecoveryPhase.POSTCHECK_VERIFIED)
                    return@withExclusiveSnapshot Result.Blocked(
                        Refusal.NOT_A_POST_MUTATION, FinalizationStep.SNAPSHOT)
                if (!journal.privateArtifactsIntact(entry))
                    return@withExclusiveSnapshot Result.Blocked(
                        Refusal.INVALID_PRIVATE_EVIDENCE, FinalizationStep.PRIVATE_ARTIFACTS)
                val receipt=(intents.inspect(entry) as?
                    MetadataCommitIntentStore.Inspection.Present)?.receipt
                    ?: return@withExclusiveSnapshot Result.Blocked(
                        Refusal.INTENT_MISSING, FinalizationStep.INTENT_BINDING)
                val uri=try { Uri.parse(entry.uri) } catch (_: Exception) {
                    return@withExclusiveSnapshot Result.Blocked(Refusal.TARGET_CHANGED)
                }
                val provider=when {
                    uri.scheme == "content" && uri.authority == "media" ->
                        MetadataDocumentProvider.MEDIASTORE
                    else -> when (MetadataReadAccessPolicy.classify(entry.sourceId,
                        uri.scheme,uri.authority)) {
                        MetadataReadAccessPolicy.Kind.MEDIASTORE ->
                            MetadataDocumentProvider.MEDIASTORE
                        MetadataReadAccessPolicy.Kind.SAF_LOCAL -> MetadataDocumentProvider.SAF
                        MetadataReadAccessPolicy.Kind.REJECT ->
                            return@withExclusiveSnapshot Result.Blocked(
                            Refusal.TARGET_CHANGED, FinalizationStep.INTENT_BINDING)
                    }
                }
                val container=try { MetadataAudioContainer.valueOf(entry.format) }
                    catch (_: Exception) {
                        return@withExclusiveSnapshot Result.Blocked(
                            Refusal.INVALID_PRIVATE_EVIDENCE,
                            FinalizationStep.INTENT_BINDING)
                    }
                if (MetadataCommitIntentManifest.verify(receipt,entry.id,target,provider,
                        container,entry.expectedBytes,entry.originalSha256,
                        entry.stageBytes,entry.stageSha256) !=
                    MetadataCommitIntentManifest.Verdict.BoundForExternalReview)
                    return@withExclusiveSnapshot Result.Blocked(
                        Refusal.INVALID_PRIVATE_EVIDENCE, FinalizationStep.PRIVATE_ARTIFACTS)
                pending=entry
                intent=receipt
                null
            }
            snapshot.value?.let { return@withContext it }
            job?.ensureActive()
            // Phase 2: cold provider readback + semantic inspection, NO journal lock.
            val actual=read.readCommittedCandidate(
                pending.sourceId,pending.uri,pending.stageBytes,pending.stageSha256,
            ) { job?.ensureActive() }
            if (actual !is MetadataCurrentBytesReader.PostCommitRead.Exact)
                return@withContext Result.Blocked(
                    Refusal.SOURCE_NOT_CANDIDATE, FinalizationStep.SOURCE_READ,
                    "READ_${(actual as MetadataCurrentBytesReader.PostCommitRead.Rejected).reason}",
                )
            // Bounded diagnostic: a retried read means MediaStore published its
            // bookkeeping size only after the commit. No URI, id or tag value.
            if (actual.announcedStampMovedDuringRead)
                android.util.Log.d("MetadataFinalize",
                    "postcheck=announcement_moved_retried lag=${actual.metadataLagObserved}")
            if (actual.actualSize != pending.stageBytes ||
                actual.actualSha256 != pending.stageSha256)
                return@withContext Result.Blocked(
                    Refusal.SOURCE_NOT_CANDIDATE, FinalizationStep.SOURCE_HASH,
                    "SHA_OR_SIZE_MISMATCH",
                )
            val checked=semantic.inspect(pending.sourceId,pending.uri,target,intent,
                userRequested=true,
                ownerCurrent={ ownerCurrent() && playbackClosed() },
                checkCancellation={ job?.ensureActive() })
            if (!ownerCurrent() || !playbackClosed())
                return@withContext Result.Blocked(
                    Refusal.TARGET_CHANGED, FinalizationStep.SOURCE_SEMANTICS,
                    "OWNER_OR_PLAYBACK_CHANGED",
                )
            if (checked !is MetadataPostMutationSourceInspector.Result.Inspected)
                return@withContext Result.Blocked(
                    Refusal.SOURCE_SEMANTICS_UNVERIFIED, FinalizationStep.SOURCE_SEMANTICS,
                    "INSPECTOR_${(checked as? MetadataPostMutationSourceInspector.Result.Blocked)?.reason?.name ?: "UNKNOWN"}",
                )
            job?.ensureActive()
            // Phase 3: promote only if the record and the private evidence are unchanged.
            val publication=journal.publishIfUnchanged(snapshot.generation,pending) {
                if (!ownerCurrent() || !playbackClosed())
                    return@publishIfUnchanged Result.Blocked(
                        Refusal.TARGET_CHANGED, FinalizationStep.JOURNAL_GENERATION)
                // The private backup and candidate are rehashed before any promotion.
                if (!journal.privateArtifactsIntact(pending))
                    return@publishIfUnchanged Result.Blocked(
                        Refusal.INVALID_PRIVATE_EVIDENCE, FinalizationStep.PRIVATE_ARTIFACTS)
                // Only the exact typed permit for this journal transaction may
                // replace the legacy global boolean.
                if (permit == null || !permit.matches(pending))
                    return@publishIfUnchanged Result.Blocked(
                        Refusal.WRITER_NOT_CERTIFIED, FinalizationStep.PERMIT,
                        "PERMIT_MISMATCH",
                    )
                val next=if (pending.phase != MetadataRecoveryPhase.POSTCHECK_VERIFIED) {
                    pending.copy(phase=MetadataRecoveryPhase.POSTCHECK_VERIFIED).also {
                        journal.advance(pending,it)
                    }
                } else pending
                // Physical bytes/fields are observed. Writer certification is
                // independently required and remains FALSE in the patch.
                when (val receipt=MetadataVerifiedReceiptGate.assessCertified(
                    permit.evidence(),
                    next.id,next.phase,next.stageSha256,
                    MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED,
                    fieldsVerified=true,ownerStillCurrent=ownerCurrent() && playbackClosed(),
                    backupIntact=journal.privateArtifactsIntact(next))) {
                    is MetadataVerifiedReceiptGate.Decision.ReadyForIndexCompare ->
                        Result.PreparedForExternalCas(receipt)
                    is MetadataVerifiedReceiptGate.Decision.Refused ->
                        Result.Blocked(Refusal.WRITER_NOT_CERTIFIED,
                            FinalizationStep.RECEIPT_GATE, receipt.reason.name)
                }
            }
            when (publication) {
                is Publication.Stale -> Result.Blocked(
                    Refusal.JOURNAL_CHANGED, FinalizationStep.JOURNAL_GENERATION)
                is Publication.Published -> publication.value
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { Result.Blocked(
              Refusal.JOURNAL_CHANGED, FinalizationStep.JOURNAL_GENERATION) }
    }

    private fun expected(entry:MetadataRecoveryJournalV1.Entry) =
        MetadataPhysicalOutcomePolicy.Expected(entry.expectedBytes,entry.originalSha256,
            entry.stageBytes,entry.stageSha256)
}
