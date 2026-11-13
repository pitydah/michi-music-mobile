package org.michimusic.data.repository

import org.michimusic.data.cache.EmbeddedTagEvidence

/** Two lightweight provider stats when the user explicitly opens details; no audio reads. */
internal object ColdMetadataStampPolicy {
    fun matches(
        owner: String, id: String, path: String, expectedSize: Long,
        before: RecoveryDocumentStamp?, after: RecoveryDocumentStamp?,
        evidence: EmbeddedTagEvidence?,
    ): Boolean = path.startsWith("content://") && id.isNotBlank() && expectedSize > 0 &&
        before != null && after != null && before == after &&
        before.size == expectedSize && before.modifiedAt > 0 &&
        evidence != null && evidence.sourceId == owner && evidence.trackId == id &&
        evidence.sizeBytes == expectedSize && evidence.modifiedAt == before.modifiedAt &&
        evidence.parserVersion == EmbeddedTagEvidence.CURRENT_PARSER_VERSION
}
