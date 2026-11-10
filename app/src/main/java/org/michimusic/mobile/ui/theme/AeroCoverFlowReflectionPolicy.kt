package org.michimusic.mobile.ui.theme

/** User-selectable CoverFlow reflection intensity. Normal preserves the UI5 baseline. */
enum class AeroCoverFlowReflection(val label: String) {
    DISCRETO("Discreto"),
    NORMAL("Normal"),
    INTENSO("Intenso"),
}

internal data class AeroCoverFlowFrameSpec(
    val borderAlpha: Float,
    val borderWidthDp: Float,
    val shadowElevationDp: Float,
)

fun savedAeroCoverFlowReflection(raw: String?): AeroCoverFlowReflection =
    AeroCoverFlowReflection.entries.firstOrNull { it.name == raw } ?: AeroCoverFlowReflection.NORMAL

/**
 * Paint-only reflection policy: it changes the static frame brightness and contact
 * elevation of the raised cover. It never mirrors or duplicates album artwork, never
 * creates a full-screen reflection and stays flat for reduced motion/high contrast.
 */
internal fun aeroCoverFlowFrame(
    mode: AeroCoverFlowReflection,
    raised: Boolean,
    reducedMotion: Boolean,
    highContrast: Boolean,
): AeroCoverFlowFrameSpec {
    if (!raised) return AeroCoverFlowFrameSpec(0f, 0f, 0f)
    if (highContrast) return AeroCoverFlowFrameSpec(0.80f, 1f, 0f)
    // Normal's border is exactly the existing AeroHairline (0x24FFFFFF).
    val borderAlpha = when (mode) {
        AeroCoverFlowReflection.DISCRETO -> 0.10f
        AeroCoverFlowReflection.NORMAL -> 36f / 255f
        AeroCoverFlowReflection.INTENSO -> 0.28f
    }
    val borderWidth = when (mode) {
        AeroCoverFlowReflection.DISCRETO -> 0.55f
        AeroCoverFlowReflection.NORMAL -> 0.65f
        AeroCoverFlowReflection.INTENSO -> 0.80f
    }
    val elevation = when (mode) {
        AeroCoverFlowReflection.DISCRETO -> 1f
        AeroCoverFlowReflection.NORMAL -> 3f
        AeroCoverFlowReflection.INTENSO -> 5f
    }.takeIf { !reducedMotion } ?: 0f
    return AeroCoverFlowFrameSpec(borderAlpha, borderWidth, elevation)
}
