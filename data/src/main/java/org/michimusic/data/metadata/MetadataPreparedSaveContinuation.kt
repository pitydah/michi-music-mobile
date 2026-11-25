package org.michimusic.data.metadata

import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataRecoveryPhase

/**
 * F1: exact, non-authorizing handle for a prepared save whose Android write
 * grant was requested. It only proves which durable transaction the UI may ask
 * to continue; the service revalidates journal, owner, manifest and artifacts.
 */
data class MetadataPreparedSaveContinuation internal constructor(
    val transactionId: String,
    val sourceId: String,
    val trackId: String,
    val uri: String,
    val originalSha256: String,
    val candidateSha256: String,
    val candidateBytes: Long,
    val ownerDigest: String,
) {
    internal fun matches(
        entry: MetadataRecoveryJournalV1.Entry,
        intent: MetadataCommitIntentManifest.Receipt,
    ): Boolean = entry.phase == MetadataRecoveryPhase.STAGE_READY &&
        transactionId == entry.id && transactionId == intent.transactionId &&
        sourceId == entry.sourceId && trackId == entry.trackId && uri == entry.uri &&
        originalSha256 == entry.originalSha256 && originalSha256 == intent.originalSha256 &&
        candidateSha256 == entry.stageSha256 && candidateSha256 == intent.candidateSha256 &&
        candidateBytes == entry.stageBytes && candidateBytes == intent.candidateBytes &&
        ownerDigest == intent.ownerSha256

    internal companion object {
        fun from(
            entry: MetadataRecoveryJournalV1.Entry,
            intent: MetadataCommitIntentManifest.Receipt,
        ): MetadataPreparedSaveContinuation? {
            if (entry.phase != MetadataRecoveryPhase.STAGE_READY ||
                entry.id != intent.transactionId ||
                entry.originalSha256 != intent.originalSha256 ||
                entry.stageSha256 != intent.candidateSha256 ||
                entry.stageBytes != intent.candidateBytes) return null
            return MetadataPreparedSaveContinuation(
                transactionId = entry.id, sourceId = entry.sourceId,
                trackId = entry.trackId, uri = entry.uri,
                originalSha256 = entry.originalSha256,
                candidateSha256 = entry.stageSha256, candidateBytes = entry.stageBytes,
                ownerDigest = intent.ownerSha256,
            )
        }
    }
}
