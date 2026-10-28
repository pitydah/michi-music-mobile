package org.michimusic.mobile.ui.components

import java.util.Locale

/** Describes decoded PCM only, never DAC-negotiated output or bit-perfect playback. */
fun decodedPcmLabel(sampleRateHz: Int, bitDepth: Int): String? {
    if (sampleRateHz !in 8_000..768_000 || bitDepth !in 1..64) return null
    val rate = sampleRateLabel(sampleRateHz) ?: return null
    return "$bitDepth bit · $rate"
}

/** Capabilities or PCM rate label; caller must state whether observed or announced. */
fun sampleRateLabel(sampleRateHz: Int): String? {
    if (sampleRateHz !in 8_000..768_000) return null
    return if (sampleRateHz % 1000 == 0) {
        "${sampleRateHz / 1000} kHz"
    } else {
        String.format(Locale.forLanguageTag("es-CL"), "%.3f", sampleRateHz / 1000.0)
            .trimEnd('0').trimEnd(',').plus(" kHz")
    }
}

/** Pure status summary, resilient to odd metadata (no negative/NaN UI). */
fun transferProgressPercent(transferred: Long, total: Long): Int? {
    if (total <= 0L) return null
    return ((transferred.coerceIn(0L, total).toDouble() / total.toDouble()) * 100.0)
        .toInt().coerceIn(0, 100)
}

/** Device count copy; count source is SyncViewModel, never estimated discovery success. */
fun linkOverviewSummary(discovered: Int, paired: Int, connected: Int): String {
    val known = discovered.coerceAtLeast(0)
    // Paired devices can be offline and no longer discoverable; do not cap
    // the trusted count to devices observed in the current discovery session.
    val linked = paired.coerceAtLeast(0)
    val live = connected.coerceAtLeast(0)
    return "$known equipos detectados · $linked vinculados · $live conectados"
}

/**
 * Correct units for Android Equalizer band gains.
 * Android Equalizer expresses band levels in millibels (mB), where 100 mB = 1 dB.
 */
fun eqGainMilliDbLabel(gainMilliDb: Int): String {
    val db = gainMilliDb / 100.0
    val normalized = if (kotlin.math.abs(db) < 0.05) 0.0 else db
    val num = String.format(Locale.forLanguageTag("es-CL"), "%.1f", normalized)
    return (if (normalized > 0) "+" else "") + num + " dB"
}

/** Band frequency, separate from sample-rate presentation. */
fun eqCenterFrequencyLabel(hz: Int): String {
    if (hz <= 0) return "—"
    if (hz < 1000) return "$hz Hz"
    val khz = hz / 1000.0
    return if (hz % 1000 == 0) "${hz / 1000} kHz" else
        String.format(Locale.forLanguageTag("es-CL"), "%.2f", khz)
            .trimEnd('0').trimEnd(',') + " kHz"
}
