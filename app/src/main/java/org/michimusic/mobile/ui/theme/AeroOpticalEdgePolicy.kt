package org.michimusic.mobile.ui.theme

/** Stable, strictly paint-only micro-light policy. No new layout layer or animation. */
internal data class AeroEdgeLight(
    val enabled: Boolean,
    val alpha: Float,
    val bottomAlpha: Float,
    val leftAlpha: Float,
)

internal fun aeroOpticalEdgeLight(
    finish: AeroFinish,
    spec: AeroFinishSpec,
    widthPx: Float,
    heightPx: Float,
): AeroEdgeLight {
    val valid = widthPx.isFinite() && heightPx.isFinite() && widthPx >= 20f && heightPx >= 12f &&
        finish != AeroFinish.Plain && finish != AeroFinish.Inset
    if (!valid) return AeroEdgeLight(false, 0f, 0f, 0f)
    val strength = spec.edgeAlpha.takeIf { it.isFinite() }?.coerceIn(0f, .12f) ?: 0f
    return AeroEdgeLight(strength > 0f, strength,
        if (finish == AeroFinish.Anodized || finish == AeroFinish.Smoked) strength * .30f else 0f,
        strength * .22f)
}
