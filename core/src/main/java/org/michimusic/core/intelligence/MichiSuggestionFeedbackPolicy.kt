package org.michimusic.core.intelligence

/**
 * Explicit, temporary "No me interesa" preference. Never trains a negative
 * artist/genre score and never produces permanent bans or background work.
 */
object MichiSuggestionFeedbackPolicy {
    const val MAX_DISMISSED = 64
    const val SNOOZE_DAYS = 30L
    const val MAX_EPOCH_DAY = 999_999_999_999L

    fun normalizedDay(day: Long): String? = day.takeIf { it in 0L..MAX_EPOCH_DAY }
        ?.toString()?.padStart(12, '0')

    fun suppressed(savedEpochDay: Long?, todayEpochDay: Long): Boolean =
        savedEpochDay != null && savedEpochDay in 0L..MAX_EPOCH_DAY &&
            todayEpochDay in 0L..MAX_EPOCH_DAY &&
            todayEpochDay >= savedEpochDay &&
            todayEpochDay - savedEpochDay < SNOOZE_DAYS
}
