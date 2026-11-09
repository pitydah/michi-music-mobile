package org.michimusic.mobile.ui.seeker

/** Number of complete interleaved samples in a bounded PCM analysis chunk. */
internal fun boundedWaveformChunkSamples(
    remainingFrames: Int,
    channels: Int,
    maxChunkFrames: Int = 2048,
): Int {
    if (remainingFrames <= 0 || channels !in 1..8 || maxChunkFrames !in 1..8192) return 0
    return minOf(remainingFrames, maxChunkFrames) * channels
}

/** Exact whole frames only. An invalid sample count is never partially decoded. */
internal fun completePcmFrames(totalBytes: Int, bytesPerSample: Int, channels: Int): Int? {
    if (totalBytes < 0 || bytesPerSample !in 1..4 || channels !in 1..8) return null
    val bytesPerFrame = bytesPerSample * channels
    if (totalBytes % bytesPerFrame != 0) return null
    return totalBytes / bytesPerFrame
}
