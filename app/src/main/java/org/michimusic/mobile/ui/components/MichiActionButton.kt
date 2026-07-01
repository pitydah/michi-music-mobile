package org.michimusic.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.GlassBg
import org.michimusic.mobile.ui.theme.GlassBorder
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary

enum class MichiButtonStyle {
    PRIMARY_GLOW,
    SECONDARY_GLASS,
}

@Composable
fun MichiActionButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MichiButtonStyle = MichiButtonStyle.PRIMARY_GLOW,
) {
    val shape = RoundedCornerShape(MichiSpacing.sm)

    val bg = when (style) {
        MichiButtonStyle.PRIMARY_GLOW -> AccentPink
        MichiButtonStyle.SECONDARY_GLASS -> GlassBg
    }
    val textColor = when (style) {
        MichiButtonStyle.PRIMARY_GLOW -> Color(0xFF090B11)
        MichiButtonStyle.SECONDARY_GLASS -> TextPrimary
    }
    val border = when (style) {
        MichiButtonStyle.PRIMARY_GLOW -> null
        MichiButtonStyle.SECONDARY_GLASS -> BorderStroke(0.5.dp, GlassBorder)
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(bg, shape)
            .then(
                if (border != null) Modifier.border(border, shape)
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = MichiSpacing.lg, vertical = MichiSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(MichiSpacing.sm))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = textColor,
            )
        }
    }
}
