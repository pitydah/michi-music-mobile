package org.michimusic.core.metadata

/**
 * ME-01: opt-in, text-only LRC timestamp correction. Does not alter the audio,
 * open a lyrics sidecar, or write an embedded tag. UI must show before/after.
 */
object MetadataLrcToolkit {
    enum class Reason { TOO_LARGE, INVALID_OFFSET, EXISTING_OFFSET_TAG, INVALID_TIMESTAMP,
        TIMESTAMP_UNDERFLOW, TIMESTAMP_OVERFLOW, PRECISION_LOSS }

    sealed interface Result {
        data class Ready(val original: String, val proposed: String, val adjusted: Int) : Result
        data class Blocked(val reason: Reason) : Result
    }

    private const val MAX_LRC_CHARS = 65536
    private const val MAX_LINE_COUNT = 3000
    private const val MAX_OFFSET_MS = 600000
    private const val MAX_TIMESTAMP_MS = 60L * 60L * 1000L * 24L
    private val timeTag = Regex("\\[(\\d{2,3}):(\\d{2})[.](\\d{2,3})]")
    private val existingOffset = Regex("(?im)^\\s*\\[offset\\s*:")

    fun shift(input: String, millis: Int): Result {
        if (input.length > MAX_LRC_CHARS || input.count { it == '\n' } > MAX_LINE_COUNT)
            return Result.Blocked(Reason.TOO_LARGE)
        if (millis !in -MAX_OFFSET_MS..MAX_OFFSET_MS) return Result.Blocked(Reason.INVALID_OFFSET)
        if (existingOffset.containsMatchIn(input)) return Result.Blocked(Reason.EXISTING_OFFSET_TAG)
        var rejected: Reason? = null
        var adjusted = 0
        val replacement = timeTag.replace(input) { match ->
            val minutes = match.groupValues[1].toInt()
            val seconds = match.groupValues[2].toInt()
            val fraction = match.groupValues[3]
            val hundredths = fraction.length == 2
            val step = if (hundredths) 10 else 1
            if (seconds > 59) { rejected = Reason.INVALID_TIMESTAMP; return@replace match.value }
            if (millis % step != 0) { rejected = Reason.PRECISION_LOSS; return@replace match.value }
            val initial = (minutes * 60L + seconds) * 1000L +
                fraction.toLong() * step
            val shifted = initial + millis
            if (shifted < 0) { rejected = Reason.TIMESTAMP_UNDERFLOW; return@replace match.value }
            if (shifted > MAX_TIMESTAMP_MS || shifted / 60000 > 999) {
                rejected = Reason.TIMESTAMP_OVERFLOW; return@replace match.value
            }
            adjusted++
            val m = (shifted / 60000).toString().padStart(match.groupValues[1].length, '0')
            val sec = ((shifted / 1000) % 60).toString().padStart(2, '0')
            val frac = ((shifted % 1000) / step).toString().padStart(fraction.length, '0')
            "[$m:$sec.$frac]"
        }
        if (rejected != null) return Result.Blocked(rejected!!)
        // A malformed bracket resembling a time tag must not silently survive a correction.
        if (Regex("\\[\\d{2,3}:\\d{2}(?:[.,]\\d+)?]").findAll(input).count() > adjusted)
            return Result.Blocked(Reason.INVALID_TIMESTAMP)
        return Result.Ready(input, replacement, adjusted)
    }
}
