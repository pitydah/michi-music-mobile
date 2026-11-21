package org.michimusic.data.metadata

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataCommitAccessPolicy
import org.michimusic.core.metadata.MetadataCommitBlock
import org.michimusic.core.metadata.MetadataCommitCertification
import org.michimusic.core.metadata.MetadataCommitGateResult
import org.michimusic.core.metadata.MetadataExactCommitRequest
import org.michimusic.core.metadata.MetadataExactGrantKind
import org.michimusic.core.metadata.MetadataPreparedCommitSnapshot
import org.michimusic.core.metadata.MetadataDocumentProvider

/**
 * ME-06: complete preflight for a PREPARED transaction. Capability certificate
 * deliberately remains FALSE, so no path mutates the original source in this
 * cut. Consent is only for the exact URI and must be renewed after state changes.
 *
 * ME-11R: the journal is observed under a brief exclusion, the exact source is
 * rehashed WITHOUT holding it, and the final verdict re-enters the journal to prove
 * that neither the record nor the private evidence changed while the provider was
 * read. A stale observation is reported as recovery-pending, never as ready.
 */
class MetadataCommitReadinessCoordinator(context: Context) {
    sealed interface Result {
        data class Blocked(val reason: MetadataCommitBlock) : Result
        data class ConsentNeeded(val pending: android.app.PendingIntent) : Result
        data object PreparedButNotCertified : Result
    }
    private val journal = MetadataRecoveryJournalV1(File(context.noBackupFilesDir,"michi-metadata-editor-v1"))
    private val permission = MetadataExactWritePermission(context)
    private val readAdapter = MetadataProviderReadAdapter(context)

    suspend fun assess(transactionId: String, userOpenedEditor: Boolean,
        ownerStillCurrent: () -> Boolean, playbackClosed: () -> Boolean): Result = withContext(Dispatchers.IO) {
        if (!userOpenedEditor) return@withContext Result.Blocked(MetadataCommitBlock.NO_EXPLICIT_USER_ACTION)
        val job = currentCoroutineContext()[Job]
        try {
            lateinit var pending: MetadataRecoveryJournalV1.Entry
            lateinit var evidence: MetadataPreparedCommitSnapshot.Evidence
            val snapshot = journal.withExclusiveSnapshot { inspection ->
                val entry = (inspection as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
                    ?: return@withExclusiveSnapshot Result.Blocked(MetadataCommitBlock.NOT_PREPARED)
                if (entry.id != transactionId)
                    return@withExclusiveSnapshot Result.Blocked(MetadataCommitBlock.NOT_PREPARED)
                val proof = MetadataPreparedCommitSnapshot.evaluate(
                    MetadataPreparedCommitSnapshot.Input(entry.id,entry.phase,entry.sourceId,
                        entry.trackId,entry.uri,entry.expectedBytes,entry.originalSha256,
                        entry.stageBytes,entry.stageSha256,entry.format))
                if (proof !is MetadataPreparedCommitSnapshot.Decision.Candidate)
                    return@withExclusiveSnapshot Result.Blocked(MetadataCommitBlock.RECOVERY_PENDING)
                if (!journal.privateArtifactsIntact(entry))
                    return@withExclusiveSnapshot Result.Blocked(MetadataCommitBlock.RECOVERY_PENDING)
                if (!ownerStillCurrent())
                    return@withExclusiveSnapshot Result.Blocked(MetadataCommitBlock.CHANGED_SOURCE)
                if (!playbackClosed())
                    return@withExclusiveSnapshot Result.Blocked(MetadataCommitBlock.PLAYBACK_ACTIVE)
                pending = entry
                evidence = proof.evidence
                null
            }
            snapshot.value?.let { return@withContext it }
            job?.ensureActive()
            val access = try {
                readAdapter.openForRead(evidence.sourceId,evidence.uri,evidence.originalBytes)
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) {
                return@withContext Result.Blocked(MetadataCommitBlock.CHANGED_SOURCE)
            }
            // The exact source is rehashed with NO journal lock held. Its result is
            // only usable if the journal is re-proved unchanged below.
            if (access.stamp.modifiedAt != pending.expectedModifiedAt ||
                !readAdapter.verifyBytes(access,evidence.originalBytes,
                    evidence.originalSha256,
                    checkCancellation = { job?.ensureActive() }))
                return@withContext Result.Blocked(MetadataCommitBlock.CHANGED_SOURCE)
            job?.ensureActive()
            if (!ownerStillCurrent())
                return@withContext Result.Blocked(MetadataCommitBlock.CHANGED_SOURCE)
            if (!playbackClosed())
                return@withContext Result.Blocked(MetadataCommitBlock.PLAYBACK_ACTIVE)
            job?.ensureActive()
            // The grant probe performs binder/provider IPC. The journal contract
            // forbids holding the exclusion across provider IPC, so it runs here,
            // unlocked. Revocation after this point is compensated by refusing the
            // stale observation below and by the hard-closed write gate.
            val permissionResult = permission.inspect(pending.sourceId, pending.uri,
                userOpenedEditor)
            if (permissionResult is MetadataExactWritePermission.Result.MediaStoreConsentRequired) {
                // A consent prompt is only meaningful for the exact observation
                // that produced it; re-prove the journal before surfacing it.
                return@withContext if (journal.isUnchanged(snapshot.generation, pending))
                    Result.ConsentNeeded(permissionResult.intent)
                else Result.Blocked(MetadataCommitBlock.RECOVERY_PENDING)
            }
            val granted =
                permissionResult == MetadataExactWritePermission.Result.ExistingExactGrant
            val publication = journal.publishIfUnchanged(snapshot.generation, pending) {
                if (!journal.privateArtifactsIntact(pending))
                    return@publishIfUnchanged Result.Blocked(MetadataCommitBlock.RECOVERY_PENDING)
                if (!ownerStillCurrent())
                    return@publishIfUnchanged Result.Blocked(MetadataCommitBlock.CHANGED_SOURCE)
                if (!playbackClosed())
                    return@publishIfUnchanged Result.Blocked(MetadataCommitBlock.PLAYBACK_ACTIVE)
                // The only current result is PREPARED_NOT_CERTIFIED. Never request
                // a write permission simply to prepare a candidate.
                if (!MetadataCommitCertification.mayMutateOriginal())
                    return@publishIfUnchanged Result.PreparedButNotCertified
                val kind = when(access.provider) {
                    MetadataDocumentProvider.MEDIASTORE -> MetadataExactGrantKind.MEDIASTORE_ITEM
                    MetadataDocumentProvider.SAF -> MetadataExactGrantKind.SAF_DOCUMENT
                    else -> return@publishIfUnchanged Result.Blocked(
                        MetadataCommitBlock.PROVIDER_NOT_RECOVERABLE)
                }
                val policy = MetadataCommitAccessPolicy.evaluate(
                    MetadataExactCommitRequest(pending.sourceId,pending.trackId,pending.uri,pending.id,
                        kind,userOpenedEditor,playbackClosed(),ownerStillCurrent(),granted,
                        providerRecoverable=false,exactTargetCurrent=true,recoveryPending=false),
                    pending.id,pending.sourceId,pending.trackId,pending.uri,kind)
                if (policy is MetadataCommitGateResult.Blocked)
                    return@publishIfUnchanged Result.Blocked(policy.reason)
                // Even a future certificate cannot bypass a missing provider
                // recovery proof. No provider source-write adapter exists yet.
                Result.Blocked(MetadataCommitBlock.PROVIDER_NOT_RECOVERABLE)
            }
            when (publication) {
                // The journal or the private evidence changed during the provider read.
                is MetadataRecoveryJournalV1.Publication.Stale ->
                    Result.Blocked(MetadataCommitBlock.RECOVERY_PENDING)
                is MetadataRecoveryJournalV1.Publication.Published -> publication.value
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { Result.Blocked(MetadataCommitBlock.RECOVERY_PENDING) }
    }
}
