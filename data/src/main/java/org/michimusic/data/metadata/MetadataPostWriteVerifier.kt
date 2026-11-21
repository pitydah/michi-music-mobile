package org.michimusic.data.metadata

import android.content.Context
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataColdFieldParser
import org.michimusic.core.metadata.MetadataFieldReadbackPolicy
import org.michimusic.core.metadata.MetadataPhysicalOutcomePolicy
import org.michimusic.core.metadata.MetadataReconciliationPolicy
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataVerifiedReceiptGate

/**
 * ME-06. STRICT read-only inspection. A source can be reported as candidate
 * bytes only when its complete SHA-256 matches the independently staged file.
 * Every changed field must then parse as the proposed value. Unverified fields
 * never yield "file saved and indexed". No automatic Room writes or restore.
 */
internal class MetadataPostWriteVerifier(context: Context) {
    data class Report(
        val physical: MetadataPhysicalOutcomePolicy.State,
        val semantic: MetadataReconciliationPolicy.State,
        val providerFailure: MetadataCurrentBytesReader.Failure? = null,
        /** Fail-closed gate: not a SAVED receipt until a certified commit exists. */
        val receiptGate: MetadataVerifiedReceiptGate.Decision =
            MetadataVerifiedReceiptGate.Decision.Refused(
                MetadataVerifiedReceiptGate.Refusal.NOT_SOURCE_MUTATION),
        /** A private non-SAVED audit record, never an authorization to mutate Room. */
        val uncertifiedAuditPersisted: Boolean = false,
    )
    private val reader=MetadataCurrentBytesReader(context)
    private val audit=MetadataUncertifiedObservationLedger(
        File(context.noBackupFilesDir,"michi-metadata-uncertified-audit-v1"))

    suspend fun verify(entry: MetadataRecoveryJournalV1.Entry,
        journal: MetadataRecoveryJournalV1, review: MetadataReview.Ready?,
        ownerStillCurrent: Boolean, checkCancellation: () -> Unit = {}): Report =
        withContext(Dispatchers.IO) {
            val expected = MetadataPhysicalOutcomePolicy.Expected(entry.expectedBytes,
                entry.originalSha256,entry.stageBytes,entry.stageSha256)
            if (!expected.valid() || !journal.privateArtifactsIntact(entry))
                return@withContext Report(MetadataPhysicalOutcomePolicy.State.INVALID_EXPECTATION,
                    MetadataReconciliationPolicy.State.BLOCKED_UNKNOWN)
            val actual=reader.read(entry.sourceId,entry.uri,
                maxOf(entry.expectedBytes,entry.stageBytes),checkCancellation)
            val physical=when(actual) {
                is MetadataCurrentBytesReader.Result.Read ->
                    MetadataPhysicalOutcomePolicy.classify(expected,actual.fingerprint)
                is MetadataCurrentBytesReader.Result.Failed ->
                    MetadataPhysicalOutcomePolicy.State.READBACK_UNAVAILABLE
            }
            val reviewMatches = review != null &&
                review.original.target.sourceId == entry.sourceId &&
                review.original.target.trackId == entry.trackId &&
                review.original.target.uri == entry.uri &&
                review.proposed.target == review.original.target &&
                review.diffs.isNotEmpty()
            val semantics=physical == MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED &&
                ownerStillCurrent && reviewMatches &&
                review?.let { verifyStageSemantics(journal.stage(entry),it) } == true
            val receiptGate=MetadataVerifiedReceiptGate.assess(
                transactionId=entry.id,phase=entry.phase,expectedStageSha256=entry.stageSha256,
                physical=physical,fieldsVerified=semantics,ownerStillCurrent=ownerStillCurrent,
                backupIntact=journal.privateArtifactsIntact(entry))
            val physicalAndFields=MetadataReconciliationPolicy.evaluate(physical,semantics,ownerStillCurrent)
            // ME-06 used ELIGIBLE_AFTER_FIELD_READBACK for structural comparison.
            // Do NOT let that be mistaken for actual permission to publish Room:
            // a certified source-mutating transaction receipt must exist too.
            val semantic=if (physicalAndFields == MetadataReconciliationPolicy.State.ELIGIBLE_AFTER_FIELD_READBACK &&
                receiptGate !is MetadataVerifiedReceiptGate.Decision.ReadyForIndexCompare)
                MetadataReconciliationPolicy.State.BLOCKED_UNVERIFIED else physicalAndFields
            val persisted = try {
                val existing = audit.inspect()
                val sequence = when (existing) {
                    MetadataUncertifiedObservationLedger.Read.Absent -> 1L
                    is MetadataUncertifiedObservationLedger.Read.Valid -> existing.record.sequence + 1
                    MetadataUncertifiedObservationLedger.Read.UntrustedKeepArtifacts -> 0L
                }
                val outcome = when (physical) {
                    MetadataPhysicalOutcomePolicy.State.ORIGINAL_INTACT ->
                        MetadataUncertifiedObservationLedger.Outcome.ORIGINAL_INTACT
                    MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED ->
                        if (semantics) MetadataUncertifiedObservationLedger.Outcome.CANDIDATE_UNCERTIFIED
                        else MetadataUncertifiedObservationLedger.Outcome.SEMANTIC_UNVERIFIED
                    MetadataPhysicalOutcomePolicy.State.READBACK_UNAVAILABLE ->
                        MetadataUncertifiedObservationLedger.Outcome.UNREADABLE
                    else -> MetadataUncertifiedObservationLedger.Outcome.UNKNOWN_BYTES
                }
                sequence > 0 && audit.record(MetadataUncertifiedObservationLedger.Record(
                    entry.id, entry.originalSha256, entry.stageSha256, outcome, sequence))
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { false }
            Report(physical, semantic,
                (actual as? MetadataCurrentBytesReader.Result.Failed)?.reason,
                receiptGate=receiptGate, uncertifiedAuditPersisted=persisted)
        }

    private fun verifyStageSemantics(stage: java.io.File, review: MetadataReview.Ready): Boolean {
        return try {
            if (!stage.isFile || Files.isSymbolicLink(stage.toPath())) false
            else FileChannel.open(stage.toPath(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS).use { channel ->
                val report = MetadataColdFieldParser.observe(channel)
                MetadataFieldReadbackPolicy.check(review,report) ==
                    MetadataFieldReadbackPolicy.Result.VERIFIED
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { false }
    }
}
