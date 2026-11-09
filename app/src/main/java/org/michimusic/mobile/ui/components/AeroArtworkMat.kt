package org.michimusic.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/**
 * A real-cover frame, not a simulated waveform or a blurred album surrogate.
 * One shaded metal lip and a calm inset. No infinite animation / no GPU blur.
 * The slot retains the existing image, source, and fallback behavior.
 */
@Composable
fun AeroArtworkMat(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    inset: Dp = 5.dp,
    emphasis: AeroArtworkEmphasis = AeroArtworkEmphasis.Standard,
    content: @Composable BoxScope.() -> Unit,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val depth = aeroArtworkDepth(emphasis, highContrast)
    val outer = RoundedCornerShape(cornerRadius + inset)
    val inner = RoundedCornerShape(cornerRadius)
    val rim = if (highContrast) {
        Brush.linearGradient(listOf(Color.White, Color.White))
    } else {
        Brush.linearGradient(listOf(
            Color(0xFF6F7985), Color(0xFF333C47), Color(0xFF212831), Color(0xFF515B68),
        ))
    }
    Box(
        modifier = modifier
            // Contact elevation is opt-in per semantic emphasis; dense rows stay flat.
            .then(
                if (depth.shadowElevationDp > 0) {
                    Modifier.shadow(depth.shadowElevationDp.dp, outer, clip = false)
                } else Modifier,
            )
            .clip(outer)
            .background(if (highContrast) SurfaceObsidian else Color(0xFF1D242D))
            .border(BorderStroke(if (highContrast) 1.5.dp else 0.65.dp, rim), outer)
            // One directional inner lip: lit at top-left, darker at bottom-right.
            // It follows the rounded frame and never paints over the source image.
            .drawWithCache {
                val insetPx = inset.toPx()
                val innerWidth = (size.width - insetPx * 2f).coerceAtLeast(0f)
                val innerHeight = (size.height - insetPx * 2f).coerceAtLeast(0f)
                val innerPath = aeroPerimeterPath(
                    inner.createOutline(Size(innerWidth, innerHeight), layoutDirection, this),
                )
                val lipBrush = Brush.linearGradient(
                    0f to Color.White.copy(alpha = depth.innerLightAlpha),
                    0.45f to Color.White.copy(alpha = depth.innerLightAlpha * 0.18f),
                    0.55f to Color.Black.copy(alpha = depth.innerShadeAlpha * 0.12f),
                    1f to Color.Black.copy(alpha = depth.innerShadeAlpha),
                    start = Offset.Zero,
                    end = Offset(size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f)),
                )
                onDrawBehind {
                    translate(insetPx, insetPx) {
                        drawPath(
                            path = innerPath,
                            brush = lipBrush,
                            style = Stroke(width = 0.55.dp.toPx(), join = StrokeJoin.Round),
                        )
                    }
                }
            }
            .padding(inset)
            .clip(inner)
            .background(SurfaceObsidian)
            .border(if (highContrast) 1.dp else 0.5.dp,
                Color.White.copy(alpha = if (highContrast) 0.85f else 0.12f), inner),
    ) {
        content()
    }
}
