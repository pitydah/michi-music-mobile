package org.michimusic.data.metadata

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataPhysicalOutcomePolicy
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataRecoveryPhasePolicy

/**
 * ME-06. On-demand recovery inspection after a crash or interrupted operation.
 * Does not write source, roll back automatically or discard possibly unique
 * backups. No background scan and no source-mutating retry.
 */
class MetadataRecoveryInspectionCoordinator(context: Context) {
    sealed interface Result {
        data object Clear : Result
        data class PreparedPrivateOnly(val transactionId: String, val phase: MetadataRecoveryPhase) : Result
        data class SourceOriginalIntact(val transactionId: String) : Result
        data class CandidateBytesPresentUncertified(val transactionId: String) : Result
        data class UnknownBytesKeepBackup(val transactionId: String) : Result
        data class PermissionOrReadbackUnavailable(val transactionId: String) : Result
        data object JournalCorruptKeepBackup : Result
        data object ConcurrentChangeBlocked : Result
    }
    private val journal = MetadataRecoveryJournalV1(File(context.noBackupFilesDir,"michi-metadata-editor-v1"))
    private val verifier = MetadataPostWriteVerifier(context)

    /** Only call on the user's explicit editor/recovery action, never during scroll. */
    suspend fun inspect(userRequested: Boolean, stillOwns: () -> Boolean): Result = withContext(Dispatchers.IO) {
        if (!userRequested) return@withContext Result.ConcurrentChangeBlocked
        try {
            // Brief exclusion, durable identity only. Artifact rehashing and source
            // readback run unlocked and are fenced by a generation recheck.
            val snapshot=journal.withExclusiveSnapshot { inspection -> inspection }
            val initial=snapshot.inspection
            val entry=(initial as? MetadataRecoveryJournalV1.Inspection.Pending)?.entry
                ?: return@withContext if(initial == MetadataRecoveryJournalV1.Inspection.Clear)
                    Result.Clear else Result.JournalCorruptKeepBackup
            if (!journal.privateArtifactsIntact(entry))
                return@withContext Result.JournalCorruptKeepBackup
            if (!MetadataRecoveryPhasePolicy.sourceMayHaveChanged(entry.phase))
                return@withContext if (journal.isUnchanged(snapshot.generation,entry))
                    Result.PreparedPrivateOnly(entry.id,entry.phase) else Result.ConcurrentChangeBlocked
            if (!stillOwns()) return@withContext Result.ConcurrentChangeBlocked
            // The entry is observed under lock and rechecked after potentially slow
            // readback. Any generation change invalidates the observation; it never
            // silently cleans the record.
            val report=verifier.verify(entry,journal,review=null,ownerStillCurrent=stillOwns())
            if (!journal.isUnchanged(snapshot.generation,entry))
                return@withContext Result.ConcurrentChangeBlocked
            when(report.physical) {
                MetadataPhysicalOutcomePolicy.State.ORIGINAL_INTACT -> Result.SourceOriginalIntact(entry.id)
                MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED ->
                    Result.CandidateBytesPresentUncertified(entry.id)
                MetadataPhysicalOutcomePolicy.State.UNKNOWN_BYTES -> Result.UnknownBytesKeepBackup(entry.id)
                else -> Result.PermissionOrReadbackUnavailable(entry.id)
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { Result.JournalCorruptKeepBackup }
    }
}
