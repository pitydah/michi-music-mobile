package org.michimusic.core.metadata

/** A user-selected library row; never a permission grant or file-system path. */
data class MetadataSelectionItem(
    val sourceId: String,
    val trackId: String,
    val uri: String,
    val sizeBytes: Long,
    val isLocal: Boolean,
)

enum class MetadataSelectionIssue {
    EMPTY_SELECTION, TOO_MANY_TRACKS, INVALID_INDEX, REMOTE_TRACK,
    INVALID_URI, INVALID_IDENTITY, MISSING_SIZE, MIXED_OWNERS,
    DUPLICATE_TRACK, DUPLICATE_URI,
}

sealed interface MetadataSelectionPlan {
    /** Indices follow the ALBUM'S observed track order, never a guessed filename order. */
    data class Ready(val indices: List<Int>) : MetadataSelectionPlan
    data class Rejected(val issue: MetadataSelectionIssue) : MetadataSelectionPlan
}

/**
 * No I/O, filename parsing, scanning or assumptions about empty sourceId: "" is
 * Michi's valid local owner. SAF roots must not be mixed in one transaction.
 */
object MetadataBatchSelectionPolicy {
    const val MAX_SELECTED = 128

    fun prepare(items: List<MetadataSelectionItem>, selected: Set<Int>): MetadataSelectionPlan {
        if (selected.isEmpty()) return MetadataSelectionPlan.Rejected(MetadataSelectionIssue.EMPTY_SELECTION)
        if (selected.size > MAX_SELECTED)
            return MetadataSelectionPlan.Rejected(MetadataSelectionIssue.TOO_MANY_TRACKS)
        if (selected.any { it !in items.indices })
            return MetadataSelectionPlan.Rejected(MetadataSelectionIssue.INVALID_INDEX)
        val indices = selected.sorted()
        val chosen = indices.map(items::get)
        if (chosen.any { !it.isLocal }) return MetadataSelectionPlan.Rejected(MetadataSelectionIssue.REMOTE_TRACK)
        if (chosen.any { !it.uri.startsWith("content://") })
            return MetadataSelectionPlan.Rejected(MetadataSelectionIssue.INVALID_URI)
        if (chosen.any { it.trackId.isBlank() })
            return MetadataSelectionPlan.Rejected(MetadataSelectionIssue.INVALID_IDENTITY)
        if (chosen.any { it.sizeBytes <= 0 })
            return MetadataSelectionPlan.Rejected(MetadataSelectionIssue.MISSING_SIZE)
        if (chosen.map { it.sourceId }.distinct().size != 1)
            return MetadataSelectionPlan.Rejected(MetadataSelectionIssue.MIXED_OWNERS)
        if (chosen.map { it.trackId }.distinct().size != chosen.size)
            return MetadataSelectionPlan.Rejected(MetadataSelectionIssue.DUPLICATE_TRACK)
        if (chosen.map { it.uri }.distinct().size != chosen.size)
            return MetadataSelectionPlan.Rejected(MetadataSelectionIssue.DUPLICATE_URI)
        return MetadataSelectionPlan.Ready(indices)
    }
}
