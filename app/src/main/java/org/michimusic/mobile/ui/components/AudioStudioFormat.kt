package org.michimusic.mobile.ui.components

import java.util.Locale

/**
 * Spanish copy for the audio studio panels.
 *
 * Both helpers exist because the panels used `Float.toString()` and the default
 * `String.format` locale, which rendered "48.0 kHz" and "-0.0 dB" inside an
 * otherwise Spanish interface. The rules are plain functions so they can be
 * unit tested without Compose.
 */
private val studioLocale: Locale = Locale.forLanguageTag("es-CL")

/**
 * Sample rate as a listener reads it: "48 kHz" for whole kilohertz values and
 * "44,1 kHz" when there is a fraction. Never "48,0 kHz".
 */
fun audioRateLabel(hz: Int): String {
    if (hz <= 0) return "$hz Hz"
    val kilohertz = hz / 1000.0
    val number = if (hz % 1000 == 0) {
        String.format(studioLocale, "%.0f", kilohertz)
    } else {
        String.format(studioLocale, "%.1f", kilohertz)
    }
    return "$number kHz"
}

/**
 * Decibel value with one decimal and a Spanish decimal comma.
 *
 * Zero never carries a minus sign ("0,0 dB" instead of "-0,0 dB"), and the
 * sign is only shown when [signed] is requested, because a headroom reserve is
 * a magnitude while a band gain is a direction.
 */
fun studioDbLabel(db: Float, signed: Boolean = false): String {
    val safe = if (db.isFinite()) db else 0f
    val magnitude = String.format(studioLocale, "%.1f", kotlin.math.abs(safe))
    if (!signed) return "$magnitude dB"
    val prefix = when {
        safe > 0.05f -> "+"
        safe < -0.05f -> "−"
        else -> ""
    }
    return "$prefix$magnitude dB"
}

/** Unitless ratio (filter Q) with two decimals and the same Spanish comma. */
fun studioRatioLabel(value: Float): String {
    val safe = if (value.isFinite()) value else 0f
    return String.format(studioLocale, "%.2f", safe)
}
