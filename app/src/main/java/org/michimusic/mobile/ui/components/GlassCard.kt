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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.GlassHighlight
import org.michimusic.mobile.ui.theme.GlassInk
import org.michimusic.mobile.ui.theme.SmokeMid
import org.michimusic.mobile.ui.theme.SurfaceBorder
import org.michimusic.mobile.ui.theme.SurfaceElevated

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Card(
        modifier = modifier.shadow(20.dp, RoundedCornerShape(8.dp), clip = false),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceElevated.copy(alpha = 0.46f),
        ),
        border = BorderStroke(0.5.dp, SurfaceBorder.copy(alpha = 1.55f)),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            GlassHighlight.copy(alpha = 0.09f),
                            SmokeMid.copy(alpha = 0.075f),
                            GlassInk.copy(alpha = 0.10f),
                        )
                    )
                )
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = 0.075f),
                            Color.Transparent,
                        ),
                        radius = 360f,
                    )
                )
                .padding(contentPadding),
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawLine(
                    color = GlassHighlight.copy(alpha = 0.11f),
                    start = androidx.compose.ui.geometry.Offset(0f, 0.5.dp.toPx()),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0.5.dp.toPx()),
                    strokeWidth = 0.7.dp.toPx(),
                )
                drawLine(
                    color = Color.Black.copy(alpha = 0.10f),
                    start = androidx.compose.ui.geometry.Offset(0f, size.height - 0.5.dp.toPx()),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5.dp.toPx()),
                    strokeWidth = 0.7.dp.toPx(),
                )
            }
            content()
        }
    }
}
