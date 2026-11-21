package org.michimusic.data.repository

import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSnapshot

/**
 * ME-11S. Values read from the Room sidecar, shown only as an explicit INDEX
 * REFERENCE. They are not physical tag evidence, they never enable editing and
 * they must never be rendered as if the file had been read.
 */
data class IndexedMetadataReference(
    val sourceId: String,
    val trackId: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val parserVersion: Int,
    val rows: List<Row>,
) {
    data class Row(val label: String, val value: String)
}

/**
 * ME-11S P0. Result of the explicit, user-initiated cold read used by the single
 * metadata editor. Unlike the historical Room-first path, the absence of a
 * sidecar row NEVER blocks reading the physical file.
 */
sealed interface MetadataEditorColdRead {
    val indexed: IndexedMetadataReference?

    /** Stable physical read with a complete observation window for the bounded field set. */
    data class Verified(
        val snapshot: MetadataSnapshot,
        override val indexed: IndexedMetadataReference?,
    ) : MetadataEditorColdRead

    /**
     * Stable physical read, but the bounded reader could not observe every field
     * in [MetadataReadFieldCatalog.primary]. Missing is not empty: unobserved
     * fields stay unverified and cannot be edited as if the tag were blank.
     */
    data class Partial(
        val snapshot: MetadataSnapshot,
        val missingFields: Set<MetadataField>,
        override val indexed: IndexedMetadataReference?,
    ) : MetadataEditorColdRead

    /**
     * The provider returned data the parser cannot prove (unsupported/truncated
     * container). Only the labelled index reference remains available.
     */
    data class IndexedOnly(
        override val indexed: IndexedMetadataReference?,
        val reason: ExtendedTagUnavailableReason,
    ) : MetadataEditorColdRead

    /** No physical proof; [indexed] may still describe what the library believes. */
    data class Unavailable(
        val reason: ExtendedTagUnavailableReason,
        override val indexed: IndexedMetadataReference? = null,
    ) : MetadataEditorColdRead
}
