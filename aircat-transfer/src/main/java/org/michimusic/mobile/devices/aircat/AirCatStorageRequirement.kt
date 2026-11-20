package org.michimusic.mobile.devices.aircat

/** The same safety margin governs UI preflight and the authoritative transfer client. */
object AirCatStorageRequirement {
    const val SAFETY_MARGIN_BYTES: Long = 100L * 1024 * 1024

    /** null means overflow; never interpret it as enough free space. */
    fun requiredBytes(payloadBytes: Long): Long? =
        if (payloadBytes < 0L) null else try {
            Math.addExact(payloadBytes, SAFETY_MARGIN_BYTES)
        } catch (_: ArithmeticException) {
            null
        }
}
