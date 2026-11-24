package org.michimusic.data.metadata

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataFieldReadReport
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataPrivateSessionIdentity
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.data.cache.CachedTrack
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
        ) : Outcome
        data class IndexPending(
            val transactionId: String,
            val snapshot: MetadataSnapshot,
            val code: String,
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
            is MetadataVerifiedCommittedSnapshotReader.Result.Verified -> read.snapshot
            is MetadataVerifiedCommittedSnapshotReader.Result.Refused ->
                return@withContext Outcome.RecoveryRequired(
                    entry.id, "COMMITTED_SNAPSHOT_NOT_VERIFIED")
        }
        val row = runCatching {
            database.trackDao().getTrackBySource(entry.sourceId, entry.trackId)
        }.getOrNull()
        val expected = preWriteIdentity(entry, row)
        if (expected != null) {
            val published = publication.reconcile(
                entry.id, evidence.original, expected, true,
                request.ownerStillCurrent, request.playbackClosed, permit)
            if (published.isIndexed()) {
                return@withContext finishIndexed(entry.id, committed)
            }
        } else if (rowApplied(entry, evidence.stage, row)) {
            return@withContext finishIndexed(entry.id, committed)
        }
        if (!writeReceipt(entry, expected, token))
            return@withContext Outcome.RecoveryRequired(
                entry.id, "INDEX_OBLIGATION_NOT_DURABLE")
        Outcome.IndexPending(entry.id, committed,
            if (expected == null) "MISSING_ROW_OR_PREIMAGE" else "CAS_REFUSED_OR_CONFLICT")
    }

    private fun finishIndexed(transactionId: String, snapshot: MetadataSnapshot): Outcome {
        if (!journal.completeVerified(transactionId))
            return Outcome.RecoveryRequired(transactionId, "INDEXED_CLEANUP_PENDING")
        return Outcome.Indexed(transactionId, snapshot)
    }

    private fun preWriteIdentity(
        entry: MetadataRecoveryJournalV1.Entry,
        row: CachedTrack?,
    ): MetadataIndexPublicationPolicy.IndexIdentity? {
        if (row == null || row.filepath != entry.uri || row.size != entry.expectedBytes)
            return null
        val verified = row.verifiedAt > 0L &&
            row.verifiedSha256 == entry.originalSha256 &&
            row.verifiedSize == row.size &&
            row.verifiedAlgorithm.lowercase() == "sha256"
        val unhashed = row.verifiedAt == 0L && row.verifiedSha256.isEmpty() &&
            row.verifiedSize == 0L && row.verifiedAlgorithm.isEmpty()
        if (!verified && !unhashed) return null
        return MetadataIndexPublicationPolicy.IndexIdentity(
            sourceId = row.sourceId, trackId = row.id, uri = row.filepath,
            version = row.verifiedAt, observedSourceSha256 = row.verifiedSha256,
        )
    }

    private fun rowApplied(
        entry: MetadataRecoveryJournalV1.Entry,
        stage: MetadataFieldReadReport,
        row: CachedTrack?,
    ): Boolean {
        if (row == null || row.filepath != entry.uri || row.size != entry.stageBytes)
            return false
        if (row.verifiedAt != 0L || row.verifiedSha256.isNotEmpty() ||
            row.verifiedSize != 0L || row.verifiedAlgorithm.isNotEmpty())
            return false
        val changes = (intents.inspect(entry) as? MetadataCommitIntentStore.Inspection.Present)
            ?.receipt?.changes ?: return false
        return changes.all { change ->
            val values = when (val observation = stage.observations[change.field]) {
                is MetadataFieldObservation.Present -> observation.values
                MetadataFieldObservation.VerifiedAbsent -> emptyList()
                else -> return false
            }
            rowValue(row, change.field) == values.firstOrNull().orEmpty()
        }
    }

    private fun rowValue(row: CachedTrack, field: MetadataField): String? = when (field) {
        MetadataField.TITLE -> row.title
        MetadataField.ARTIST -> row.artist
        MetadataField.ALBUM -> row.album
        MetadataField.GENRE -> row.genre
        else -> null
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
            else -> return null
        }
        if (intent.container != container) return null
        return MetadataWritePermit(
            transactionId = entry.id, sourceId = entry.sourceId,
            trackId = entry.trackId, uri = entry.uri,
            originalSha256 = entry.originalSha256,
            candidateSha256 = entry.stageSha256, stageBytes = entry.stageBytes,
            profileId = MediaStoreMp3TextProfile.ID,
            evidenceId = MediaStoreMp3TextProfile.EVIDENCE_ID,
            container = entry.format, revision = revision,
            fields = intent.changes.mapTo(linkedSetOf()) { it.field },
            explicitUserAction = true,
        ).takeIf { it.matches(entry) }
    }

    private fun writeReceipt(
        entry: MetadataRecoveryJournalV1.Entry,
        expected: MetadataIndexPublicationPolicy.IndexIdentity?,
        token: String,
    ): Boolean = MetadataIndexReconciliationReceipt.write(root,
        MetadataIndexReconciliationReceipt(
            transactionId = entry.id, sourceId = entry.sourceId,
            trackId = entry.trackId, uri = entry.uri,
            originalSha256 = entry.originalSha256,
            candidateSha256 = entry.stageSha256,
            candidateBytes = entry.stageBytes, format = entry.format,
            ownerEvidenceToken = token,
            previousVerifiedAt = expected?.version ?: 0L,
            previousVerifiedSha256 = expected?.observedSourceSha256.orEmpty(),
            previousSize = entry.expectedBytes,
        ))

    private fun MetadataFinalizedIndexPublicationCoordinator.Result.isIndexed(): Boolean = when (this) {
        is MetadataFinalizedIndexPublicationCoordinator.Result.IndexedOnly -> true
        is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredGenreIndexed -> true
        is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleIndexed -> true
        is MetadataFinalizedIndexPublicationCoordinator.Result.StructuredPeopleAndGenreIndexed -> true
        else -> false
    }
}
