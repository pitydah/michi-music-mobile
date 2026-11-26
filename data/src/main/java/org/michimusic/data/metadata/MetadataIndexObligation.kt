package org.michimusic.data.metadata

import java.io.File
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataIndexPublicationPolicy
import org.michimusic.core.metadata.MetadataPrivateSessionIdentity
import org.michimusic.core.metadata.MetadataTarget

/**
 * ME-13 C1: one durable obligation format for a certified MP3 transaction.
 * A new obligation is written only as an actionable V2 receipt. There is no
 * V1 fallback: a readable-but-unusable record would hide a recovery failure.
 */
internal object MetadataIndexObligation {
    fun write(
        root: File,
        entry: MetadataRecoveryJournalV1.Entry,
        target: MetadataTarget,
        intent: MetadataCommitIntentManifest.Receipt?,
        expectedIndex: MetadataIndexPublicationPolicy.IndexIdentity?,
    ): Boolean {
        if (intent == null || !MetadataCommitIntentManifest.valid(intent)) return false
        if (intent.transactionId != entry.id ||
            intent.candidateSha256 != entry.stageSha256 ||
            intent.candidateBytes != entry.stageBytes ||
            intent.originalSha256 != entry.originalSha256 ||
            intent.originalBytes != entry.expectedBytes) return false
        if (target.sourceId != entry.sourceId || target.trackId != entry.trackId ||
            target.uri != entry.uri || target.evidenceToken.isBlank()) return false
        if (runCatching { MetadataPrivateSessionIdentity.digest(target) }.getOrNull() !=
            intent.ownerSha256) return false
        val receipt = MetadataIndexReconciliationReceipt(
            transactionId = entry.id, sourceId = entry.sourceId,
            trackId = entry.trackId, uri = entry.uri,
            originalSha256 = entry.originalSha256,
            candidateSha256 = entry.stageSha256,
            candidateBytes = entry.stageBytes, format = entry.format,
            ownerEvidenceToken = target.evidenceToken,
            previousVerifiedAt = expectedIndex?.version ?: 0L,
            previousVerifiedSha256 = expectedIndex?.observedSourceSha256.orEmpty(),
            previousSize = entry.expectedBytes,
            previousMetadataRevision = expectedIndex?.metadataRevision ?: 0L,
        )
        val actionable = receipt.actionable
        val written = actionable &&
            MetadataIndexReconciliationReceipt.write(root, receipt)
        return written
    }
}
