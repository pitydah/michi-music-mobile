package org.michimusic.mobile.ui.seeker

/** PCM8 is unsigned in Android AudioFormat; map silence 128 to zero. */
internal fun normalizePcm8(raw: Byte): Float = ((raw.toInt() and 0xff) - 128) / 128f

/** Signed little-endian PCM32 scaled without Int.MIN_VALUE overflow. */
internal fun normalizePcm32(raw: Int): Float = raw.toDouble().div(2_147_483_648.0).toFloat()

/** Malformed codec outputs are never interpreted as partial frames. */
internal fun validPcmOutputRange(offset: Int, size: Int, capacity: Int, bytesPerFrame: Int): Boolean =
    offset >= 0 && size >= 0 && capacity >= 0 &&
        bytesPerFrame > 0 && size <= capacity && offset <= capacity - size &&
        size % bytesPerFrame == 0

/** Cache only when the asset has not changed while its samples were decoded. */
internal fun stableWaveformAsset(
    beforeSize: Long, beforeModified: Long, afterSize: Long, afterModified: Long,
): Boolean = beforeSize > 0L && beforeModified > 0L &&
    beforeSize == afterSize && beforeModified == afterModified
