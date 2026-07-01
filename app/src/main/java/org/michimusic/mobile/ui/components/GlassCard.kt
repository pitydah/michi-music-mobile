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
import org.michimusic.mobile.ui.theme.MichiRadius
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.SurfaceBorder
import org.michimusic.mobile.ui.theme.SurfaceElevated

enum class GlassCardVariant {
    NORMAL,
    COMPACT,
    STRONG,
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    variant: GlassCardVariant = GlassCardVariant.NORMAL,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(MichiRadius.card)
    val innerPadding = when (variant) {
        GlassCardVariant.COMPACT -> MichiSpacing.md
        GlassCardVariant.NORMAL -> MichiSpacing.lg
        GlassCardVariant.STRONG -> MichiSpacing.lg
    }

    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = SurfaceElevated,
        ),
        border = BorderStroke(0.5.dp, SurfaceBorder),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (variant == GlassCardVariant.STRONG) 4.dp else 0.dp,
        ),
    ) {
        Box(
            modifier = Modifier.padding(innerPadding),
            content = content,
        )
    }
}
