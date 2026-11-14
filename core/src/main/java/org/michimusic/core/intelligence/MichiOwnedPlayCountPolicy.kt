package org.michimusic.core.intelligence

/**
 * Evidence of successful, local, owner-verified completions **since Intelligence opt-in**.
 * Missing records are UNKNOWN, not zero; the former ID-only Room counters are excluded.
 * This policy is pure, small and has no IO, scheduler, model or network dependency.
 */
object MichiOwnedPlayCountPolicy {
    const val MAX_TRACK_COUNTERS = 128
    const val MAX_COUNT = 255
    const val MAX_EPOCH_DAY = 999_999_999_999L

    data class Count(val completed: Int, val lastCompletedEpochDay: Long)

    fun addCompleted(previous: Count?, epochDay: Long): Count? {
        if (epochDay !in 0L..MAX_EPOCH_DAY) return previous
        val latestDay = maxOf(previous?.lastCompletedEpochDay ?: epochDay, epochDay)
        return Count(((previous?.completed ?: 0) + 1).coerceAtMost(MAX_COUNT), latestDay)
    }

    fun encode(count: Count): String =
        count.lastCompletedEpochDay.toString().padStart(12, '0') + "|" + count.completed

    fun decode(raw: String): Count? {
        if (raw.length !in 14..16 || raw.getOrNull(12) != '|') return null
        val day = raw.substring(0, 12).toLongOrNull() ?: return null
        val count = raw.substring(13).toIntOrNull() ?: return null
        if (day !in 0L..MAX_EPOCH_DAY || count !in 1..MAX_COUNT) return null
        return Count(count, day)
    }
}
