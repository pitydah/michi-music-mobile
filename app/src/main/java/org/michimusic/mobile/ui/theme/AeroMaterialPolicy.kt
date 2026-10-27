package org.michimusic.mobile.ui.theme

/** The single role taxonomy used by legacy GlassCard, semantic surfaces and screens. */
enum class AeroSurfaceRole { Canvas, Content, Raised, Floating }

/** Pure visual contract: separates design decisions from Compose rendering. */
data class AeroMaterialRule(
    val borderOpacity: Float,
    val borderWidthDp: Float,
    val elevationDp: Int,
)

fun aeroMaterialRule(role: AeroSurfaceRole, highContrast: Boolean): AeroMaterialRule {
    if (highContrast) return AeroMaterialRule(
        borderOpacity = if (role == AeroSurfaceRole.Canvas) 0f else 0.9f,
        borderWidthDp = if (role == AeroSurfaceRole.Canvas) 0f else 1f,
        elevationDp = 0,
    )
    return when (role) {
        AeroSurfaceRole.Canvas -> AeroMaterialRule(0f, 0f, 0)
        AeroSurfaceRole.Content -> AeroMaterialRule(0.12f, 0.6f, 0)
        AeroSurfaceRole.Raised -> AeroMaterialRule(0.16f, 0.65f, 1)
        AeroSurfaceRole.Floating -> AeroMaterialRule(0.23f, 0.75f, 5)
    }
}
