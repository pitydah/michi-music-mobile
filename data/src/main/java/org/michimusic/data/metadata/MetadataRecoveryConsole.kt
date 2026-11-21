package org.michimusic.data.metadata

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataRecoveryActionPolicy
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataRecoveryPhasePolicy

/**
 * ME-07: manually invoked, exact-track recovery console. Its only destructive
 * capability deletes PRIVATE prepared artifacts before any source mutation.
 * It NEVER restores, writes, truncates or renames an audio provider document.
 * No background inspection and no release of any possibly unique backup.
 */
class MetadataRecoveryConsole(context: Context) {
    private val journal=MetadataRecoveryJournalV1(
        File(context.noBackupFilesDir,"michi-metadata-editor-v1"))

    sealed interface Status {
        data object NoPending : Status
        data object OtherTrackHasPendingWork : Status
        data class DiscardablePrivateStage(val transactionId: String,
            val phase: MetadataRecoveryPhase) : Status
        data class ProtectedKeepBackup(val phase: MetadataRecoveryPhase?) : Status
        data object CorruptOrInaccessibleKeepBackup : Status
        data object ActionMustBeExplicit : Status
    }
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
                            Status.ProtectedKeepBackup(candidate.phase)
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
            else Status.ProtectedKeepBackup(entry.phase)
            if (!journal.isUnchanged(observation.generation,entry))
                return@withContext Status.CorruptOrInaccessibleKeepBackup
            status
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { Status.CorruptOrInaccessibleKeepBackup }
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
