package org.michimusic.data.metadata

import org.michimusic.core.metadata.MetadataSafRenameIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataSafRenameIndexReplayPolicy
import org.michimusic.core.metadata.MetadataSafRenameRecoveryPolicy
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.TrackDao

/**
 * ME-11Q. After a provider-specific independent postcheck, relocate exactly ONE
 * existing index row. Never rekeys a track ID, invents a hash, or touches media.
 * No UI may enable this pending external provider and Room instrumented trials.
 */
internal object MetadataSafRenameRoomPublicationCertification {
    const val URI_CAS_CERTIFIED = false
}

internal class MetadataSafRenameRoomIndexPort(private val dao: TrackDao) {
    sealed interface Result {
        data object NotCertified : Result
        data object MissingOrStaleIndex : Result
        data class Blocked(val reason: MetadataSafRenameIndexPublicationPolicy.Reason) : Result
        data object ConcurrentModification : Result
        /** New URI may reflect an interrupted CAS: never infer a completed commit. */
        data object NeedsManualPostCasReconciliation : Result
        data object PublishedIndexOnly : Result
    }

    suspend fun publish(
        intent: MetadataSafRenameRecoveryPolicy.Intent,
        freshVerdict: MetadataSafRenameRecoveryPolicy.Verdict,
        conditions: MetadataSafRenameIndexPublicationPolicy.Preconditions,
    ): Result {
        if (!MetadataSafRenameRoomPublicationCertification.URI_CAS_CERTIFIED)
            return Result.NotCertified
        val row = dao.getTrackBySource(intent.sourceId,intent.trackId)
            ?: return Result.MissingOrStaleIndex
        // A crash between Room CAS and any future final receipt leaves the
        // durable rename journal terminal. Classify the cached URI first,
        // without assuming that seeing the new URI proves this transaction.
        val replay = MetadataSafRenameIndexReplayPolicy.classify(
            intent, freshVerdict, row.asIndex(),
            conditions.renameJournalTerminalVerified,
            conditions.mainRecoveryJournalClear,
            conditions.ownerStillCurrent, conditions.playbackClosed)
        when (replay) {
            MetadataSafRenameIndexReplayPolicy.Decision.ManualPostCasReconciliation ->
                return Result.NeedsManualPostCasReconciliation
            is MetadataSafRenameIndexReplayPolicy.Decision.Refused ->
                return Result.Blocked(when (replay.reason) {
                    MetadataSafRenameIndexReplayPolicy.Reason.JOURNAL_NOT_TERMINAL ->
                        MetadataSafRenameIndexPublicationPolicy.Reason.UNVERIFIED_JOURNAL
                    MetadataSafRenameIndexReplayPolicy.Reason.MAIN_RECOVERY_PENDING ->
                        MetadataSafRenameIndexPublicationPolicy.Reason.OTHER_RECOVERY_PENDING
                    MetadataSafRenameIndexReplayPolicy.Reason.OWNER_CHANGED ->
                        MetadataSafRenameIndexPublicationPolicy.Reason.OWNER_CHANGED
                    MetadataSafRenameIndexReplayPolicy.Reason.PLAYBACK_ACTIVE ->
                        MetadataSafRenameIndexPublicationPolicy.Reason.PLAYBACK_ACTIVE
                    MetadataSafRenameIndexReplayPolicy.Reason.INDEPENDENT_RENAME_NOT_VERIFIED ->
                        MetadataSafRenameIndexPublicationPolicy.Reason.INDEPENDENT_READBACK_UNVERIFIED
                    else -> MetadataSafRenameIndexPublicationPolicy.Reason.WRONG_INDEX_IDENTITY
                })
            MetadataSafRenameIndexReplayPolicy.Decision.EvaluateFirstCas -> Unit
        }
        val decision = MetadataSafRenameIndexPublicationPolicy.decide(
            intent,freshVerdict,row.asIndex(),conditions)
        if (decision is MetadataSafRenameIndexPublicationPolicy.Decision.Refused)
            return Result.Blocked(decision.reason)
        decision as MetadataSafRenameIndexPublicationPolicy.Decision.Ready
        // Room CAS guarantees old URI and all prior cached scalars/proof must
        // still match. Evidence for the old URI is atomically invalidated.
        return if (dao.commitVerifiedSafRenameLocation(
                sourceId=row.sourceId,trackId=row.id,
                oldUri=decision.previousUri,newUri=decision.currentUri,
                previousSize=row.size,oldVerifiedSize=row.verifiedSize,
                oldVerifiedSha256=row.verifiedSha256,oldVerifiedAt=row.verifiedAt,
                oldVerifiedAlgorithm=row.verifiedAlgorithm,
                previousTitle=row.title,previousArtist=row.artist,
                previousAlbum=row.album,previousAlbumArtist=row.albumArtist,
                previousGenre=row.genre,previousTrackNumber=row.trackNumber,
                previousYear=row.year,
            )) Result.PublishedIndexOnly else Result.ConcurrentModification
    }

    private fun CachedTrack.asIndex() = MetadataSafRenameIndexPublicationPolicy.Indexed(
        sourceId,id,filepath,size,verifiedSize,verifiedSha256,verifiedAt,verifiedAlgorithm)
}
