package org.michimusic.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.SurfaceBorder
import org.michimusic.mobile.ui.theme.SurfaceElevated

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceElevated,
        ),
        border = BorderStroke(0.5.dp, SurfaceBorder),
    ) {
        Box(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}
