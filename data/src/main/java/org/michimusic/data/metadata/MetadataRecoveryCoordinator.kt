package org.michimusic.data.metadata

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataPrivateSessionIdentity
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.data.cache.MichiDatabase

/**
 * ME-13 P0-A: durable, draft-free recovery of an already started MP3 write.
 *
 * The editor is not an input. This coordinator proves the exact journal,
 * owner receipt, durable intent and both private artifacts, builds the original
 * snapshot from the backup, and asks the single post-mutation finalizer to
 * confirm the provider source. It never opens the source for writing and never
 * re-runs `overwriteWithPermit`. A confirmed source is then published through
 * the same exact CAS as a fresh save; a missing row leaves a durable V2 receipt.
 */
class MetadataRecoveryCoordinator internal constructor(
    context: Context,
    private val database: MichiDatabase,
) {
    data class RecoveryRequest(
        val transactionId: String,
        val sourceId: String,
        val trackId: String,
        val uri: String,
        val userRequested: Boolean,
        val ownerStillCurrent: () -> Boolean,
        val playbackClosed: () -> Boolean,
    )

    sealed interface Outcome {
        data class Indexed(
            val transactionId: String,
            val snapshot: MetadataSnapshot,
            val committedBytes: Long = 0L,
            val committedSha256: String = "",
        ) : Outcome
        data class IndexPending(
            val transactionId: String,
            val snapshot: MetadataSnapshot,
            val code: String,
            val committedBytes: Long = 0L,
            val committedSha256: String = "",
        ) : Outcome
        data class RecoveryRequired(
            val transactionId: String,
            val code: String,
        ) : Outcome
        data class Blocked(val code: String) : Outcome
    }

    private val root = File(context.noBackupFilesDir, "michi-metadata-editor-v1")
    private val journal = MetadataRecoveryJournalV1(root)
    private val intents = MetadataCommitIntentStore(journal)
    private val owners = MetadataRecoveryOwnerStore(root)
    private val preimages = MetadataIndexPreimageStore(root)
    private val finalization = MetadataPostMutationFinalizationCoordinator(context)
    private val publication = MetadataFinalizedIndexPublicationCoordinator(context, database)
    private val committedSnapshots = MetadataVerifiedCommittedSnapshotReader(context)

    suspend fun resume(request: RecoveryRequest): Outcome = withContext(Dispatchers.IO) {
        if (!request.userRequested) return@withContext Outcome.Blocked("ACTION_REQUIRED")
        if (!request.ownerStillCurrent() || !request.playbackClosed())
            return@withContext Outcome.Blocked("OWNER_OR_PLAYBACK_CHANGED")
        val entry = (journal.inspect() as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
            ?: return@withContext Outcome.Blocked("JOURNAL_UNAVAILABLE")
        if (entry.id != request.transactionId || entry.sourceId != request.sourceId ||
            entry.trackId != request.trackId || entry.uri != request.uri)
            return@withContext Outcome.Blocked("WRONG_TRANSACTION_OR_OWNER")
        if (entry.phase !in setOf(
                MetadataRecoveryPhase.MUTATING,
                MetadataRecoveryPhase.MUTATION_UNKNOWN,
                MetadataRecoveryPhase.POSTCHECK_VERIFIED,
            ))
            return@withContext Outcome.Blocked("NOT_POST_MUTATION_PHASE")
        val intent = (intents.inspect(entry) as? MetadataCommitIntentStore.Inspection.Present)
            ?.receipt ?: return@withContext Outcome.RecoveryRequired(
            entry.id, "INTENT_NOT_DURABLE")
        // A post-mutation transaction must carry its own owner receipt. Falling
        // back to the journal token when that file is missing or corrupt would
        // turn an unverifiable owner into a recoverable one.
        val owner = owners.read(entry)
            ?: return@withContext Outcome.RecoveryRequired(
                entry.id,
                if (entry.ownerEvidenceToken.isBlank()) "LEGACY_OWNER_EVIDENCE_MISSING"
                else "OWNER_RECEIPT_MISSING_OR_CORRUPT")
        val token = owner.evidenceToken
        if (owner.ownerSha256 != intent.ownerSha256)
            return@withContext Outcome.Blocked("OWNER_EVIDENCE_MISMATCH")
        val target = MetadataTarget(entry.sourceId, entry.trackId, entry.uri, token)
        if (MetadataPrivateSessionIdentity.digest(target) != intent.ownerSha256)
            return@withContext Outcome.Blocked("OWNER_EVIDENCE_MISMATCH")
        val evidence = when (val rebuilt = MetadataDurableEvidenceBuilder.rebuild(
            journal, entry, intent, target)) {
            is MetadataDurableEvidenceBuilder.Result.Rebuilt -> rebuilt
            is MetadataDurableEvidenceBuilder.Result.Refused ->
                return@withContext Outcome.RecoveryRequired(entry.id, rebuilt.code)
        }
        val permit = permitFor(entry, intent)
            ?: return@withContext Outcome.Blocked("PERMIT_NOT_BINDABLE")

        // The single postcheck authority. This is the only call that may advance
        // the journal to POSTCHECK_VERIFIED, and it never writes source bytes.
        when (val confirmed = finalization.confirm(
            entry.id, target, true, request.ownerStillCurrent,
            request.playbackClosed, permit)) {
            is MetadataPostMutationFinalizationCoordinator.Result.PreparedForExternalCas -> Unit
            is MetadataPostMutationFinalizationCoordinator.Result.Blocked ->
                return@withContext Outcome.RecoveryRequired(
                    entry.id, "POSTCHECK:${confirmed.step}:${confirmed.code}")
            is MetadataPostMutationFinalizationCoordinator.Result.ComparedButNotCertified ->
                return@withContext Outcome.RecoveryRequired(
                    entry.id, "POSTCHECK_NOT_CERTIFIED")
        }

        val committed = when (val read = committedSnapshots.read(
            entry, target, intent,
        )) {
            is MetadataVerifiedCommittedSnapshotReader.Result.Verified -> read
            is MetadataVerifiedCommittedSnapshotReader.Result.Refused ->
                return@withContext Outcome.RecoveryRequired(
                    entry.id, "COMMITTED_SNAPSHOT_NOT_VERIFIED")
        }
        val preimage = when (val stored = preimages.read(entry)) {
            is MetadataIndexPreimageStore.Result.Read -> stored.preimage
            is MetadataIndexPreimageStore.Result.Refused ->
                return@withContext Outcome.RecoveryRequired(entry.id, stored.code)
        }
        val expected = preWriteIdentity(entry, preimage)
        if (expected != null) {
            val published = publication.reconcile(
                entry.id, evidence.original, expected, true,
                request.ownerStillCurrent, request.playbackClosed, permit)
            if (published.isIndexed()) {
                return@withContext finishIndexed(entry, committed)
            }
        }
        if (appliedMarkerMatches(entry, preimage)) {
            return@withContext finishIndexed(entry, committed)
        }
        if (!writeReceipt(entry, expected, token))
            return@withContext Outcome.RecoveryRequired(
                entry.id, "INDEX_OBLIGATION_NOT_DURABLE")
        Outcome.IndexPending(entry.id, committed.snapshot,
            if (expected == null) "MISSING_ROW_OR_PREIMAGE" else "CAS_REFUSED_OR_CONFLICT",
            committed.physicalBytes, committed.physicalSha256)
    }

    private fun finishIndexed(
        entry: MetadataRecoveryJournalV1.Entry,
        committed: MetadataVerifiedCommittedSnapshotReader.Result.Verified,
    ): Outcome {
        val proof = MetadataIndexAppliedReceipt(
            transactionId = entry.id, sourceId = entry.sourceId,
            trackId = entry.trackId, uri = entry.uri,
            candidateSha256 = entry.stageSha256, candidateBytes = entry.stageBytes,
        )
        if (!MetadataIndexAppliedReceipt.write(root, proof))
            return Outcome.RecoveryRequired(entry.id, "INDEX_APPLIED_PROOF_NOT_DURABLE")
        if (!journal.completeVerified(entry.id))
            return Outcome.RecoveryRequired(entry.id, "INDEXED_CLEANUP_PENDING")
        MetadataIndexAppliedReceipt.delete(root, entry.id)
        return Outcome.Indexed(entry.id, committed.snapshot,
            committed.physicalBytes, committed.physicalSha256)
    }

    private fun preWriteIdentity(
        entry: MetadataRecoveryJournalV1.Entry,
        preimage: MetadataIndexPreimage,
    ): MetadataIndexPublicationPolicy.IndexIdentity? {
        if (preimage !is MetadataIndexPreimage.Existing) return null
        if (preimage.uri != entry.uri || preimage.size != entry.expectedBytes ||
            preimage.sourceId != entry.sourceId || preimage.trackId != entry.trackId)
            return null
        val verified = preimage.verifiedAt > 0L &&
            preimage.verifiedSha256 == entry.originalSha256 &&
            preimage.verifiedSize == preimage.size &&
            preimage.verifiedAlgorithm.lowercase() == "sha256"
        val unhashed = preimage.verifiedAt == 0L && preimage.verifiedSha256.isEmpty() &&
            preimage.verifiedSize == 0L && preimage.verifiedAlgorithm.isEmpty()
        if (!verified && !unhashed) return null
        return MetadataIndexPublicationPolicy.IndexIdentity(
            sourceId = preimage.sourceId, trackId = preimage.trackId, uri = preimage.uri,
            version = preimage.verifiedAt,
            observedSourceSha256 = preimage.verifiedSha256,
            metadataRevision = preimage.metadataRevision,
        )
    }

    private suspend fun appliedMarkerMatches(
        entry: MetadataRecoveryJournalV1.Entry,
        preimage: MetadataIndexPreimage,
    ): Boolean {
        val existing = preimage as? MetadataIndexPreimage.Existing ?: return false
        val marker = runCatching {
            database.trackDao().getAppliedMetadataTransaction(entry.id)
        }.getOrNull() ?: return false
        return marker.sourceId == entry.sourceId &&
            marker.trackId == entry.trackId &&
            marker.uri == entry.uri &&
            marker.originalRevision == existing.metadataRevision &&
            marker.committedRevision > marker.originalRevision &&
            marker.candidateSha256 == entry.stageSha256 &&
            marker.candidateBytes == entry.stageBytes
    }

    private fun permitFor(
        entry: MetadataRecoveryJournalV1.Entry,
        intent: MetadataCommitIntentManifest.Receipt,
    ): MetadataWritePermit? {
        val container = runCatching { MetadataAudioContainer.valueOf(entry.format) }
            .getOrNull() ?: return null
        val revision = when (container) {
            MetadataAudioContainer.ID3_V23 -> 3
            MetadataAudioContainer.ID3_V24 -> 4
            MetadataAudioContainer.FLAC -> 1
            else -> return null
        }
        if (intent.container != container) return null
        val profile = org.michimusic.core.metadata.MetadataWriteProfileRegistry
            .forContainer(container) ?: return null
        return MetadataWritePermit(
            transactionId = entry.id, sourceId = entry.sourceId,
            trackId = entry.trackId, uri = entry.uri,
            originalSha256 = entry.originalSha256,
            candidateSha256 = entry.stageSha256, stageBytes = entry.stageBytes,
            profileId = profile.id,
            evidenceId = profile.capabilityEvidenceId,
            container = entry.format, revision = revision,
            fields = intent.changes.mapTo(linkedSetOf()) { it.field },
            explicitUserAction = true,
            resources = intent.resources,
        ).takeIf { it.matches(entry) }
    }

    private fun writeReceipt(
        entry: MetadataRecoveryJournalV1.Entry,
        expected: MetadataIndexPublicationPolicy.IndexIdentity?,
        token: String,
    ): Boolean {
        val receipt = MetadataIndexReconciliationReceipt(
            transactionId = entry.id, sourceId = entry.sourceId,
            trackId = entry.trackId, uri = entry.uri,
            originalSha256 = entry.originalSha256,
            candidateSha256 = entry.stageSha256,
            candidateBytes = entry.stageBytes, format = entry.format,
            ownerEvidenceToken = token,
            previousVerifiedAt = expected?.version ?: 0L,
            previousVerifiedSha256 = expected?.observedSourceSha256.orEmpty(),
            previousSize = entry.expectedBytes,
            previousMetadataRevision = expected?.metadataRevision ?: 0L,
        )
        return receipt.actionable && MetadataIndexReconciliationReceipt.write(root, receipt)
    }

    private fun MetadataFinalizedIndexPublicationCoordinator.Result.isIndexed(): Boolean = when (this) {
        is MetadataFinalizedIndexPublicationCoordinator.Result.IndexedOnly -> true
        is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredGenreIndexed -> true
        is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleIndexed -> true
        is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleAndGenreIndexed -> true
        else -> false
    }
}
