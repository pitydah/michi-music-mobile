package org.michimusic.data.metadata

import org.michimusic.core.metadata.CertifiedWriteEvidence
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataField

/**
 * ME-13: a typed, transaction-bound authorization. It is not a global gate and
 * cannot be reused for another URI, track, source hash, candidate or field set.
 */
internal data class MetadataWritePermit(
    val transactionId: String,
    val sourceId: String,
    val trackId: String,
    val uri: String,
    val originalSha256: String,
    val candidateSha256: String,
    val stageBytes: Long,
    val profileId: String,
    val evidenceId: String,
    val container: String,
    val revision: Int,
    val fields: Set<MetadataField>,
    val explicitUserAction: Boolean,
) {
    fun evidence(): CertifiedWriteEvidence = CertifiedWriteEvidence(
        transactionId = transactionId, profileId = profileId, evidenceId = evidenceId,
        sourceId = sourceId, trackId = trackId, uri = uri,
        originalSha256 = originalSha256, candidateSha256 = candidateSha256,
        candidateBytes = stageBytes,
    )

    fun matches(entry: MetadataRecoveryJournalV1.Entry): Boolean =
        explicitUserAction &&
            transactionId == entry.id && sourceId == entry.sourceId &&
            trackId == entry.trackId && uri == entry.uri &&
            originalSha256 == entry.originalSha256 &&
            candidateSha256 == entry.stageSha256 &&
            stageBytes == entry.stageBytes &&
            profileId == MediaStoreMp3TextProfile.ID &&
            evidenceId == MediaStoreMp3TextProfile.EVIDENCE_ID &&
            container == entry.format &&
            revision in setOf(3, 4) &&
            fields.isNotEmpty() && fields.all { it in MediaStoreMp3TextProfile.profile.supportedFields } &&
            entry.uri.startsWith("content://media/")
}
