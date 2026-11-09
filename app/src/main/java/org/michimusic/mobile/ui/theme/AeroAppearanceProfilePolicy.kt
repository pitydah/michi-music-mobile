package org.michimusic.mobile.ui.theme

/**
 * UI5 appearance policy. Existing VisualPreferencesRepository persists the enabled
 * Discreto/Equilibrado profiles and microtextures; Glass eligibility remains separate.
 */
enum class AeroAppearanceProfile { DISCRETO, EQUILIBRADO, CRISTALINO }

/** Named surfaces permitted to request banner-image blur, never per-row glass. */
enum class AeroOpticalGlassSurface {
    PLAYLIST_BANNER,
    AUDIO_ROUTE_SELECTOR,
    DOCK,
    MINIPLAYER,
    OTHER,
}

enum class AeroGlassDecision { SMOKED_FALLBACK, BANNER_IMAGE_BLUR }

/**
 * A banner blur requires a loaded source image, enough rendering budget and an
 * explicitly eligible surface. The banner-image renderer is the only implemented optics.
 */
fun aeroGlassDecision(
    profile: AeroAppearanceProfile,
    surface: AeroOpticalGlassSurface,
    bannerImageAvailable: Boolean,
    performanceAllowed: Boolean,
    highContrast: Boolean,
): AeroGlassDecision {
    if (profile != AeroAppearanceProfile.CRISTALINO ||
        !bannerImageAvailable || !performanceAllowed || highContrast
    ) return AeroGlassDecision.SMOKED_FALLBACK
    return when (surface) {
        AeroOpticalGlassSurface.PLAYLIST_BANNER -> AeroGlassDecision.BANNER_IMAGE_BLUR
        // AERO-OPTICS-07: no safe authentic backdrop capture exists between the
        // now-playing screen and this technical sheet. Android 12 RenderEffect can
        // blur the sheet's own content, not the window underneath, so Smoked is the
        // honest fallback. Revisit only with a verified cross-window source.
        AeroOpticalGlassSurface.AUDIO_ROUTE_SELECTOR,
        AeroOpticalGlassSurface.DOCK,
        AeroOpticalGlassSurface.MINIPLAYER,
        AeroOpticalGlassSurface.OTHER -> AeroGlassDecision.SMOKED_FALLBACK
    }
}

/**
 * Intensity tuning is subordinate to high contrast and the canonical finish tokens.
 * CRISTALINO does not fake transparency: its opt-in difference is glass eligibility.
 * Every resulting alpha remains within the existing Aero <= 0.12 contract.
 */
fun aeroAppearanceFinishSpec(
    finish: AeroFinish,
    profile: AeroAppearanceProfile,
    highContrast: Boolean,
    microtexturesEnabled: Boolean,
): AeroFinishSpec {
    val base = aeroFinishSpec(finish, highContrast)
    if (highContrast) return base
    val intensity = when (profile) {
        AeroAppearanceProfile.DISCRETO -> 0.55f
        AeroAppearanceProfile.EQUILIBRADO,
        AeroAppearanceProfile.CRISTALINO -> 1f
    }
    return base.copy(
        topLightAlpha = (base.topLightAlpha * intensity).coerceIn(0f, 0.12f),
        grainAlpha = if (microtexturesEnabled)
            (base.grainAlpha * intensity).coerceIn(0f, 0.12f) else 0f,
        edgeAlpha = (base.edgeAlpha * intensity).coerceIn(0f, 0.12f),
        // Inset depth communicates a control's role, so it is not attenuated.
        insetShadeAlpha = base.insetShadeAlpha,
    )
}
