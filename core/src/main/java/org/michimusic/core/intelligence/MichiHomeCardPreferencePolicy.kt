package org.michimusic.core.intelligence

/**
 * UI-only, bounded per-card preferences. These do not alter artist/genre learning,
 * queue order, or playback. There are exactly five known card kinds.
 */
object MichiHomeCardPreferencePolicy {
    enum class Choice { DEFAULT, PINNED, HIDDEN }

    fun encode(choice: Choice): String? = when (choice) {
        Choice.DEFAULT -> null // no row is necessary
        Choice.PINNED -> "p"
        Choice.HIDDEN -> "h"
    }

    fun decode(raw: String?): Choice = when (raw) {
        "p" -> Choice.PINNED
        "h" -> Choice.HIDDEN
        else -> Choice.DEFAULT // unknown value cannot grant additional authority
    }

    /** Pinning changes only visual order; it cannot add an unproposed card. */
    fun arrange(
        cards: List<MichiHomeIntelligencePolicy.Card>,
        preferences: Map<MichiHomeIntelligencePolicy.CardKind, Choice>,
        maxVisible: Int = 3,
    ): List<MichiHomeIntelligencePolicy.Card> {
        if (maxVisible <= 0) return emptyList()
        val visible = cards.filter { preferences[it.kind] != Choice.HIDDEN }
        return (visible.filter { preferences[it.kind] == Choice.PINNED } +
            visible.filter { preferences[it.kind] != Choice.PINNED })
            .take(maxVisible.coerceAtMost(3))
    }
}
