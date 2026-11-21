package org.michimusic.data.metadata

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.michimusic.core.metadata.MetadataSafRenameIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataSafRenameRecoveryPolicy
import org.michimusic.data.cache.TrackDao

/**
 * ME-11Q. Explicit, one-target readback -> terminal journal check -> Room CAS.
 * This is SOURCE-WRITE NO-GO and INDEX-PUBLISH NO-GO until the provider and
 * stable-ID contracts have been certified on real Android devices.
 * No implicit background recovery, retry, UI success or media mutation.
 */
internal class MetadataSafRenameIndexPublicationCoordinator(
    private val context: Context,
    private val sourceJournal: MetadataRecoveryJournalV1,
    private val renameJournal: MetadataSafRenameJournal,
    private val dao: TrackDao,
) {
    enum class Refusal {
        UNCERTIFIED, NO_VERIFIED_RENAME_RECEIPT, OWNER_OR_PLAYBACK_CHANGED,
        READBACK_REJECTED, RECEIPT_CHANGED, SECOND_VERDICT_NOT_RENAMED,
    }
    sealed interface Result {
        data class Refused(val reason: Refusal) : Result
        data class Index(val result: MetadataSafRenameRoomIndexPort.Result) : Result
    }

    suspend fun publishAfterSecondColdRead(
        request: MetadataSafRenameAndroidEvidenceReader.Request,
        /** Only a provider-profile certificate, never an arbitrary UI switch. */
        stableTrackIdentityCertified: Boolean = false,
        ownerStillCurrentNow: () -> Boolean,
        playbackClosedNow: () -> Boolean,
    ): Result {
        if (!MetadataSafRenameCertification.mayRenameOriginal() ||
            !MetadataSafRenameRoomPublicationCertification.URI_CAS_CERTIFIED)
            return Result.Refused(Refusal.UNCERTIFIED)
        val snapshot = sourceJournal.withExclusiveSnapshot {
            if (it != MetadataRecoveryJournalV1.Inspection.Clear) null
            else renameJournal.inspect() as? MetadataSafRenameJournal.State.Pending
        }
        val pending = snapshot.value ?: return Result.Refused(Refusal.NO_VERIFIED_RENAME_RECEIPT)
        if (pending.entry.phase != MetadataSafRenameJournal.Phase.RENAMED_VERIFIED)
            return Result.Refused(Refusal.NO_VERIFIED_RENAME_RECEIPT)
        if (!ownerStillCurrentNow() || !playbackClosedNow())
            return Result.Refused(Refusal.OWNER_OR_PLAYBACK_CHANGED)
        val evidence = try {
            MetadataSafRenameAndroidEvidenceReader(context).observe(
                pending.entry.intent,request,pending.entry.returnedDocumentUri)
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { return Result.Refused(Refusal.READBACK_REJECTED) }
        if (evidence !is MetadataSafRenameAndroidEvidenceReader.Result.Observed)
            return Result.Refused(Refusal.READBACK_REJECTED)
        val freshVerdict = MetadataSafRenameRecoveryPolicy.reconcile(
            pending.entry.intent,
            evidence.evidence.copy(returnedDocumentUri=pending.entry.returnedDocumentUri),
        )
        if (freshVerdict !is MetadataSafRenameRecoveryPolicy.Verdict.RenamedVerified)
            return Result.Refused(Refusal.SECOND_VERDICT_NOT_RENAMED)
        // Fence before the Room CAS: the conditional UPDATE is the atomic publication,
        // but the receipt and the main journal must still be the same observation.
        val fence = sourceJournal.publishIfUnchanged(snapshot.generation) {
            renameJournal.inspect() == pending &&
                ownerStillCurrentNow() && playbackClosedNow()
        }
        if (fence !is MetadataRecoveryJournalV1.Publication.Published || fence.value != true)
            return Result.Refused(Refusal.RECEIPT_CHANGED)
        val flags = MetadataSafRenameIndexPublicationPolicy.Preconditions(
            renameJournalTerminalVerified=true,
            mainRecoveryJournalClear=true,
            providerListingCertified=request.providerListingCertified,
            trackIdStableAcrossDocumentRename=stableTrackIdentityCertified,
            ownerStillCurrent=ownerStillCurrentNow(),
            playbackClosed=playbackClosedNow(),
        )
        return Result.Index(MetadataSafRenameRoomIndexPort(dao).publish(
            pending.entry.intent,freshVerdict,flags))
        // No automatic journal discard here. A future separately verified
        // completion protocol must handle a kill between CAS and final receipt.
    }
}
