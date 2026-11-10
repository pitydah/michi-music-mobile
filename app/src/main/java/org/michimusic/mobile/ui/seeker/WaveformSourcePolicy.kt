package org.michimusic.mobile.ui.seeker

/**
 * UI5.11: PCM analysis is restricted to a local, readable media location.
 * A remote-control host and streaming track never trigger disk/network analysis.
 * This is deliberately separate from Signal Truth's playback evidence.
 */
internal fun eligibleWaveformPath(
    filepath: String,
    streamingSource: Boolean,
    remoteHost: Boolean,
): String? {
    if (streamingSource || remoteHost) return null
    val candidate = filepath.trim()
    if (candidate.isBlank() || candidate.length > 4096) return null
    return when {
        candidate.startsWith("content://", ignoreCase = true) -> candidate
        candidate.startsWith("file:///", ignoreCase = true) -> candidate
        candidate.startsWith("/") -> candidate
        else -> null // never analyze http(s), remote peers, synthetic IDs or relative paths
    }
}

internal fun waveformFrameBudget(durationUs: Long, sampleRate: Int): Long? {
    // Bounded offline work: long mixes/podcasts fall back to the original seeker.
    if (durationUs <= 0L || durationUs > 12L * 60L * 1_000_000L || sampleRate !in 8_000..384_000)
        return null
    val frames = (durationUs.toDouble() * sampleRate / 1_000_000.0).toLong()
    return frames.takeIf { it in 16L..40_000_000L }
}
