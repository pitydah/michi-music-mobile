package org.michimusic.data.repository

import org.michimusic.core.models.Track

/** Strict reconciliation of a committed 32-file page with the in-memory snapshot. */
internal object RecoveryPageCommitPolicy {
    fun apply(
        previous: List<Track>, proposals: Map<Int, Track>,
        applied: Set<Pair<String, String>>, owner: String, mediaStore: Boolean,
    ): List<Track> {
        if (proposals.isEmpty()) return previous
        return previous.mapIndexed { index, existing ->
            val proposed = proposals[index] ?: return@mapIndexed existing
            // A stale receipt/cross-owner proposal must never update visible cache.
            if (proposed.sourceId != existing.sourceId ||
                proposed.id != existing.id || proposed.filepath != existing.filepath ||
                proposed.size != existing.size || existing.sourceId != owner ||
                (!mediaStore && (owner to existing.id) !in applied)) existing
            else proposed
        }
    }
}
