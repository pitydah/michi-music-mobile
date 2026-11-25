package org.michimusic.data.metadata

import org.michimusic.core.metadata.CertifiedWriteEvidence
import org.michimusic.core.metadata.MetadataWriteProfileRegistry
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataCommitIntentManifest

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
    val resources: List<MetadataCommitIntentManifest.ResourceChange> = emptyList(),
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
            MetadataWriteProfileRegistry.byId(profileId)?.let { profile ->
                evidenceId == profile.capabilityEvidenceId &&
                    container == entry.format &&
                    (fields.isNotEmpty() || resources.isNotEmpty()) &&
                    fields.all { it in profile.supportedFields } &&
                    profile.supports(resources) &&
                    profile.containers.any { it.name == container } &&
                    (when (profile.id) {
                        org.michimusic.core.metadata.MediaStoreMp3TextProfile.ID ->
                            revision in setOf(3, 4)
                        org.michimusic.core.metadata.MediaStoreFlacTextProfile.ID ->
                            revision == 1
                        org.michimusic.core.metadata.MediaStoreM4aTextProfile.ID ->
                            revision == 1
                        else -> false
                    }) &&
                    entry.uri.startsWith("content://media/")
            } == true
}
