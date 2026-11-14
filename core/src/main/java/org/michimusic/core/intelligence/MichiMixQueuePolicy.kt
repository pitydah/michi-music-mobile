package org.michimusic.core.intelligence

/**
 * A Michi Mix is a separate, verified queue, never an entry point into allTracks.
 * The caller must independently prove file availability and current local authority.
 */
object MichiMixQueuePolicy {
    const val MAX_MIX_TRACKS = 6

    data class Plan(val startIndex: Int, val keys: List<MichiIntelligencePolicy.TrackKey>)

    fun plan(
        orderedKeys: List<MichiIntelligencePolicy.TrackKey>,
        selected: MichiIntelligencePolicy.TrackKey? = null,
    ): Plan? {
        if (orderedKeys.isEmpty() || orderedKeys.size > MAX_MIX_TRACKS ||
            orderedKeys.any { it.trackId.isBlank() } ||
            orderedKeys.distinct().size != orderedKeys.size
        ) return null
        val index = if (selected == null) 0 else orderedKeys.indexOf(selected)
        if (index < 0) return null
        return Plan(index, orderedKeys.toList())
    }
}
