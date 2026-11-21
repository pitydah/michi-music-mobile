package org.michimusic.data.repository

import org.michimusic.core.metadata.MetadataFieldReadReport
import org.michimusic.core.metadata.MetadataReadFieldCatalog
import org.michimusic.core.metadata.MetadataSnapshot

/**
 * ME-11S P0. Pure classification of one explicit cold read.
 *
 * The provider/stamp probes live in the repository; this policy decides what the
 * evidence is allowed to mean. It deliberately keeps "unobserved" distinct from
 * "empty": only fields with a complete observation enter the editable window,
 * and everything else is reported as [MetadataEditorColdRead.Partial].
 */
internal object MetadataEditorColdReadPolicy {
    fun classify(
        expectedSize: Long,
        before: RecoveryDocumentStamp,
        after: RecoveryDocumentStamp,
        legacy: EmbeddedTagReadResult,
        report: MetadataFieldReadReport?,
        indexed: IndexedMetadataReference?,
        snapshotOf: (EmbeddedTagFields, RecoveryDocumentStamp, MetadataFieldReadReport?) -> MetadataSnapshot,
    ): MetadataEditorColdRead {
        if (before.size != expectedSize)
            return MetadataEditorColdRead.Unavailable(
                ExtendedTagUnavailableReason.SIZE_MISMATCH, indexed)
        if (before.modifiedAt <= 0)
            return MetadataEditorColdRead.Unavailable(
                ExtendedTagUnavailableReason.MODIFIED_AT_ZERO, indexed)
        val fields = when (legacy) {
            is EmbeddedTagReadResult.Parsed -> legacy.fields
            is EmbeddedTagReadResult.NoGenreTag -> legacy.fields
            EmbeddedTagReadResult.PermissionDenied ->
                return MetadataEditorColdRead.Unavailable(
                    ExtendedTagUnavailableReason.PERMISSION_DENIED, indexed)
            EmbeddedTagReadResult.ReadFailed ->
                return MetadataEditorColdRead.Unavailable(
                    ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE, indexed)
            EmbeddedTagReadResult.Unverifiable ->
                return MetadataEditorColdRead.IndexedOnly(
                    indexed, ExtendedTagUnavailableReason.TAG_READER_UNSUPPORTED)
        }
        // A stamp that moves between the two probes means the bytes may belong to
        // a different document revision than the one the user opened. Refuse.
        if (after.size != before.size || after.modifiedAt != before.modifiedAt)
            return MetadataEditorColdRead.Unavailable(
                ExtendedTagUnavailableReason.DOCUMENT_CHANGED_DURING_READ, indexed)
        val snapshot = snapshotOf(fields, after, report)
        val missing = MetadataReadFieldCatalog.primary - snapshot.verifiedFields
        return if (missing.isEmpty()) {
            MetadataEditorColdRead.Verified(snapshot, indexed)
        } else {
            MetadataEditorColdRead.Partial(snapshot, missing, indexed)
        }
    }
}
