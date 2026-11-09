package org.michimusic.mobile.ui.theme

/** UI5's five supported seeker designs. Original remains the deterministic default. */
enum class AeroSeekerStyle(val label: String) {
    ORIGINAL("Aero Original"),
    WAVY("Aero Wavy"),
    WAVEFORM_BARS("Waveform · Barras"),
    WAVEFORM_CONTINUOUS("Waveform · Continuo"),
    LIQUID_GLASS("Aero Liquid Glass"),
}

/** Reject corrupt preferences without changing accessibility or playback state. */
fun savedAeroSeekerStyle(raw: String?): AeroSeekerStyle =
    AeroSeekerStyle.entries.firstOrNull { it.name == raw } ?: AeroSeekerStyle.ORIGINAL

/** Actual amplitudes only. Unknown/invalid/empty values never become a fake waveform. */
fun usableSeekerAmplitudes(samples: List<Float>?): Boolean =
    samples != null && samples.size in 16..1024 && samples.all { it.isFinite() && it in 0f..1f }

/** A missing decoder output gracefully reuses the exact legacy seeker, including its gestures. */
fun resolvedAeroSeekerStyle(
    requested: AeroSeekerStyle,
    amplitudes: List<Float>?,
    highContrast: Boolean = false,
): AeroSeekerStyle = when {
    highContrast && requested != AeroSeekerStyle.ORIGINAL -> AeroSeekerStyle.ORIGINAL
    requested == AeroSeekerStyle.WAVEFORM_BARS || requested == AeroSeekerStyle.WAVEFORM_CONTINUOUS ->
        if (usableSeekerAmplitudes(amplitudes)) requested else AeroSeekerStyle.ORIGINAL
    else -> requested
}

/** Motion only while active; gestures must temporarily flatten decorative waves. */
fun aeroWavyMotionAllowed(
    style: AeroSeekerStyle,
    playing: Boolean,
    reducedMotion: Boolean,
    dragging: Boolean,
): Boolean = style == AeroSeekerStyle.WAVY && playing && !reducedMotion && !dragging

/** TalkBack explanation when a requested waveform cannot be shown, never fake data. */
fun aeroSeekerFallbackDescription(
    requested: AeroSeekerStyle,
    resolved: AeroSeekerStyle,
    highContrast: Boolean,
): String? = when {
    highContrast && requested != AeroSeekerStyle.ORIGINAL ->
        "Control de progreso original por contraste alto"
    requested in setOf(AeroSeekerStyle.WAVEFORM_BARS, AeroSeekerStyle.WAVEFORM_CONTINUOUS) &&
        resolved == AeroSeekerStyle.ORIGINAL ->
        "Forma de onda no disponible; barra de progreso original"
    else -> null
}
