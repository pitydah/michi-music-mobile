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

/** Motion only while active; gestures freeze the decorative wave, never fake data. */
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

/**
 * Visible status for Now Playing only. A waveform that was actually attempted and
 * still has no usable real amplitudes must not look like a style that silently did
 * nothing. ORIGINAL/WAVY/LIQUID_GLASS are complete designs on their own, high
 * contrast deliberately uses the legacy track, and an in-flight decode stays
 * quiet instead of claiming a failure too early.
 */
fun aeroSeekerVisibleFallback(
    requested: AeroSeekerStyle,
    amplitudes: List<Float>?,
    highContrast: Boolean,
    waveformSettled: Boolean,
): String? = when {
    !waveformSettled || highContrast -> null
    requested != AeroSeekerStyle.WAVEFORM_BARS && requested != AeroSeekerStyle.WAVEFORM_CONTINUOUS -> null
    usableSeekerAmplitudes(amplitudes) -> null
    else -> "Forma de onda no disponible; barra original"
}
