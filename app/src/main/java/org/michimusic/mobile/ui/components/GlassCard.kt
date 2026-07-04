package org.michimusic.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.GlassHighlight
import org.michimusic.mobile.ui.theme.GlassInk
import org.michimusic.mobile.ui.theme.GlassVeil
import org.michimusic.mobile.ui.theme.SmokeMid
import org.michimusic.mobile.ui.theme.SurfaceBorder
import org.michimusic.mobile.ui.theme.SurfaceElevated

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 16.dp,
    accent: Color = AccentCoral,
    content: @Composable BoxScope.() -> Unit,
) {
    Card(
        modifier = modifier.shadow(18.dp, RoundedCornerShape(8.dp), clip = false),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceElevated.copy(alpha = 0.40f),
        ),
        border = BorderStroke(
            0.35.dp,
            Brush.linearGradient(
                listOf(
                    GlassHighlight.copy(alpha = 0.16f),
                    SurfaceBorder.copy(alpha = 0.95f),
                    accent.copy(alpha = 0.09f),
                    SurfaceBorder.copy(alpha = 0.75f),
                )
            ),
        ),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            GlassHighlight.copy(alpha = 0.062f),
                            SmokeMid.copy(alpha = 0.055f),
                            GlassInk.copy(alpha = 0.08f),
                        )
                    )
                )
                .background(
                    Brush.radialGradient(
                        listOf(
                            accent.copy(alpha = 0.045f),
                            Color.Transparent,
                        ),
                        radius = 360f,
                    )
                )
                .padding(contentPadding),
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val specular = Path().apply {
                    moveTo(w * 0.04f, h * 0.18f)
                    cubicTo(w * 0.24f, h * 0.02f, w * 0.54f, h * 0.04f, w * 0.86f, h * 0.14f)
                }
                drawPath(
                    path = specular,
                    color = GlassVeil.copy(alpha = 0.07f),
                    style = Stroke(width = 0.55.dp.toPx()),
                )
                drawCircle(
                    color = GlassHighlight.copy(alpha = 0.038f),
                    radius = w * 0.42f,
                    center = androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.02f),
                )
                drawCircle(
                    color = GlassInk.copy(alpha = 0.055f),
                    radius = w * 0.45f,
                    center = androidx.compose.ui.geometry.Offset(w * 0.92f, h * 1.05f),
                )
                drawLine(
                    color = GlassHighlight.copy(alpha = 0.075f),
                    start = androidx.compose.ui.geometry.Offset(w * 0.06f, 0.5.dp.toPx()),
                    end = androidx.compose.ui.geometry.Offset(w * 0.94f, 0.5.dp.toPx()),
                    strokeWidth = 0.45.dp.toPx(),
                )
                drawLine(
                    color = Color.Black.copy(alpha = 0.055f),
                    start = androidx.compose.ui.geometry.Offset(w * 0.08f, h - 0.5.dp.toPx()),
                    end = androidx.compose.ui.geometry.Offset(w * 0.92f, h - 0.5.dp.toPx()),
                    strokeWidth = 0.45.dp.toPx(),
                )
                val dotStep = 30.dp.toPx()
                var y = 12.dp.toPx()
                var row = 0
                while (y < h) {
                    var x = if (row % 2 == 0) 12.dp.toPx() else 24.dp.toPx()
                    while (x < w) {
                        drawCircle(
                            color = GlassHighlight.copy(alpha = 0.010f),
                            radius = 0.35.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(x, y),
                        )
                        x += dotStep
                    }
                    row += 1
                    y += dotStep
                }
            }
            content()
        }
    }
}
