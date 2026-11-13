package org.michimusic.data.repository

import org.michimusic.data.cache.EmbeddedTagEvidence

/** The provider may replace a document without changing its URI or byte count. */
internal data class RecoveryDocumentStamp(val size: Long, val modifiedAt: Long)

/** No timestamp/size provenance = no cache mutation. Never hash full audio files. */
internal object RecoveryFingerprintPolicy {
    fun isCurrent(
        sourceId: String,
        trackId: String,
        indexedSize: Long,
        before: RecoveryDocumentStamp?,
        after: RecoveryDocumentStamp?,
        indexedEvidence: EmbeddedTagEvidence?,
        isMediaStore: Boolean,
    ): Boolean {
        if (indexedSize <= 0 || before == null || after == null || before != after ||
            before.size != indexedSize || before.modifiedAt <= 0L) return false
        // MediaStore's current provider fingerprint is checked both before/after the
        // explicit read. SAF also needs the fingerprint of the indexed Room snapshot.
        if (isMediaStore) return true
        return indexedEvidence != null && indexedEvidence.sourceId == sourceId &&
            indexedEvidence.trackId == trackId && indexedEvidence.sizeBytes == indexedSize &&
            indexedEvidence.modifiedAt == before.modifiedAt &&
            indexedEvidence.parserVersion == EmbeddedTagEvidence.CURRENT_PARSER_VERSION
    }
}
