package org.michimusic.data.metadata

import kotlinx.coroutines.CancellationException
import org.michimusic.core.metadata.MetadataCommitCertification
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataSourceIndexDelta
import org.michimusic.core.metadata.MetadataVerifiedReceiptGate

/**
 * ME-10V: narrow, testable CAS boundary for post-write Room reconciliation.
 * The port is supplied by the existing data/repository layer in a future
 * integration; this class never fetches the library or mutates it itself.
 * No port method is called unless the globally certified writer gate is open,
 * which is false in this patch. A stale index is never overwritten.
 */
internal class MetadataIndexCasCoordinator(private val port: Port) {
    interface Port {
        suspend fun current(targetSource: String, targetTrack: String):
            MetadataIndexPublicationPolicy.IndexIdentity?
        /** Must perform an ATOMIC compare/version check in one Room transaction. */
        suspend fun updateIfVersion(expected: MetadataIndexPublicationPolicy.IndexIdentity,
                                    delta: MetadataSourceIndexDelta.Delta): Boolean
    }
    enum class Refusal { NOT_CERTIFIED, NO_VERIFIED_RECEIPT, STALE_INDEX,
        OWNER_MISMATCH, SOURCE_UNVERIFIED, PROVIDER_FAILURE, ATOMIC_CAS_REFUSED }
    sealed interface Result {
        data class Blocked(val reason: Refusal) : Result
        /** DB update was attempted, not proof that the Android UI was refreshed. */
        data class AppliedToIndexOnly(val transactionId: String) : Result
    }
    suspend fun attempt(
        expected: MetadataIndexPublicationPolicy.IndexIdentity,
        prepared: MetadataSourceIndexDelta.Result,
        phase: MetadataRecoveryPhase,
        verifiedReceipt: MetadataVerifiedReceiptGate.Decision,
        playbackClosed: Boolean,
        ownerCurrent: () -> Boolean,
        permit: MetadataWritePermit? = null,
    ): Result {
        val delta=(prepared as? MetadataSourceIndexDelta.Result.PreparedForExternalCas)?.delta
            ?: return Result.Blocked(Refusal.SOURCE_UNVERIFIED)
        val scoped = permit != null &&
            permit.transactionId == delta.transactionId &&
            permit.candidateSha256 == delta.sourceSha256 &&
            permit.stageBytes == delta.candidateBytes &&
            permit.uri == delta.target.uri &&
            permit.sourceId == delta.target.sourceId &&
            permit.trackId == delta.target.trackId
        if (!scoped && !MetadataCommitCertification.mayMutateOriginal())
            return Result.Blocked(Refusal.NOT_CERTIFIED)
        if (!ownerCurrent() || expected.sourceId != delta.target.sourceId ||
            expected.trackId != delta.target.trackId || expected.uri != delta.target.uri)
            return Result.Blocked(Refusal.OWNER_MISMATCH)
        // No forged caller boolean can manufacture a certified receipt.
        val decision=MetadataIndexPublicationPolicy.assess(MetadataIndexPublicationPolicy.Request(
            expected,expected,delta.transactionId,delta.sourceSha256,phase,
            verifiedReceipt,semanticReadbackVerified=true,playbackClosed=playbackClosed))
        if (decision !is MetadataIndexPublicationPolicy.Decision.PreparedCas)
            return Result.Blocked(Refusal.NO_VERIFIED_RECEIPT)
        return try {
            val now=port.current(expected.sourceId,expected.trackId)
                ?: return Result.Blocked(Refusal.STALE_INDEX)
            if (!ownerCurrent() || now != expected)
                return Result.Blocked(Refusal.STALE_INDEX)
            if (!port.updateIfVersion(expected,delta))
                Result.Blocked(Refusal.ATOMIC_CAS_REFUSED)
            else Result.AppliedToIndexOnly(delta.transactionId)
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: Exception) { Result.Blocked(Refusal.PROVIDER_FAILURE) }
    }
}
