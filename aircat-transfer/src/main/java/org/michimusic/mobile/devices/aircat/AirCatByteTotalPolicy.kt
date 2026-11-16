package org.michimusic.mobile.devices.aircat

/** A25: checked recovery byte totals before touching committed journals or I/O. */
internal object AirCatByteTotalPolicy {
    private const val MAX_ITEMS = 10_000

    /** Null means invalid, overflow or excessive batch; no partial progress receipt. */
    fun exactTotal(sizes: Collection<Long>): Long? {
        if (sizes.size > MAX_ITEMS) return null
        var total = 0L
        for (size in sizes) {
            if (size <= 0L) return null
            total = try { Math.addExact(total, size) }
            catch (_: ArithmeticException) { return null }
        }
        return total
    }
}
