package org.michimusic.mobile.ui.theme

/**
 * One effective material result for specialized Aero components.
 *
 * Role validity, appearance profile, microtextures, high contrast and press/focus
 * feedback are resolved once here so a component cannot invent a second finish.
 */
data class AeroResolvedMaterial(
    val finish: AeroFinish,
    val spec: AeroFinishSpec,
)

fun aeroResolveMaterial(
    role: AeroSurfaceRole,
    requestedFinish: AeroFinish,
    profile: AeroAppearanceProfile,
    highContrast: Boolean,
    microtexturesEnabled: Boolean,
    interactionActive: Boolean = false,
): AeroResolvedMaterial {
    val finish = aeroResolvedFinish(role, requestedFinish, highContrast)
    val base = aeroAppearanceFinishSpec(finish, profile, highContrast, microtexturesEnabled)
    return AeroResolvedMaterial(
        finish = finish,
        spec = aeroInteractiveFinishSpec(finish, base, interactionActive, highContrast),
    )
}
