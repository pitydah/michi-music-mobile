package org.michimusic.data.repository

/**
 * Immutable selection of missing-genre work. Cursor advances even when a probe fails,
 * so a file with unsupported tags cannot permanently starve later library entries.
 * No timer, thread, service or persistent worker is created.
 */
internal object GenreBackfill {
    data class Batch(val indices: List<Int>, val nextCursor: Int, val missing: Int)

    fun nextBatch(
        eligible: List<Boolean>, cursor: Int, budget: Int, remainingHint: Int? = null,
    ): Batch {
        val missing = remainingHint ?: eligible.count { it }
        if (eligible.isEmpty() || budget <= 0) return Batch(emptyList(), 0, missing)
        val start = cursor.coerceAtLeast(0) % eligible.size
        val indexes = ArrayList<Int>(minOf(budget, eligible.size))
        var inspected = 0
        var position = start
        while (inspected < eligible.size && indexes.size < budget) {
            if (eligible[position]) indexes.add(position)
            position = (position + 1) % eligible.size
            inspected++
        }
        return Batch(indexes, position, missing)
    }
}
