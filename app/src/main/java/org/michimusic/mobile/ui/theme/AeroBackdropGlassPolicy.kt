package org.michimusic.mobile.ui.theme

/**
 * An explicit opt-in for ONE existing bitmap banner, never an arbitrary list row.
 * Android 12+ blur is bounded to the existing playlist banner; the original
 * bitmap stays the source. This does not claim AGSL refraction or certify GPU.
 */
internal fun aeroPlaylistBannerBlurEnabled(
    profile: AeroAppearanceProfile,
    sdkInt: Int,
    bannerLoaded: Boolean,
    highContrast: Boolean,
    reducedMotion: Boolean,
    powerSaveMode: Boolean = false,
    lowRamDevice: Boolean = false,
): Boolean = sdkInt >= 31 && bannerLoaded && !reducedMotion &&
    !powerSaveMode && !lowRamDevice &&
    aeroGlassDecision(
        profile = profile,
        surface = AeroOpticalGlassSurface.PLAYLIST_BANNER,
        bannerImageAvailable = true,
        performanceAllowed = true, // Opt-in, one bounded bitmap only.
        highContrast = highContrast,
    ) == AeroGlassDecision.BANNER_IMAGE_BLUR

/** Maintains readable text when the actual banner varies in brightness. */
internal fun aeroPlaylistPanelOpacity(glassEnabled: Boolean, highContrast: Boolean): Float =
    if (highContrast) 1f else if (glassEnabled) .84f else .92f

/**
 * Top of the ONLY blurred strip: the existing information tray. Returns null
 * when glass is off or layout bounds are not usable, so the base banner stays
 * sharp and no second, full-image blur is ever requested.
 */
internal fun aeroPlaylistGlassRegionTopPx(
    heroHeightPx: Int,
    trayHeightPx: Int,
    glassEnabled: Boolean,
): Int? = if (!glassEnabled || heroHeightPx <= 0 || trayHeightPx <= 0 ||
    trayHeightPx >= heroHeightPx
) null else heroHeightPx - trayHeightPx

/** The selected profile and the *effective* visual behavior are different states. */
internal fun glassSuspensionReason(
    profile: AeroAppearanceProfile,
    sdkInt: Int,
    highContrast: Boolean,
    reducedMotion: Boolean,
    powerSaveMode: Boolean,
    lowRamDevice: Boolean,
): String? = when {
    profile != AeroAppearanceProfile.CRISTALINO -> null
    highContrast -> "Contraste alto: desenfoque suspendido"
    reducedMotion -> "Movimiento reducido: desenfoque suspendido"
    sdkInt < 31 -> "Android anterior a 12: acabado Aero compatible"
    powerSaveMode -> "Ahorro de batería: desenfoque suspendido"
    lowRamDevice -> "Memoria limitada: desenfoque suspendido"
    else -> null // Still requires an actual, loaded image.
}
