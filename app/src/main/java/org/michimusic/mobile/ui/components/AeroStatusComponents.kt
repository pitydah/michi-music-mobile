package org.michimusic.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion
import org.michimusic.mobile.ui.motion.AeroObservedText
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary

/** Lightweight, motion-free status language shared across Michi ecosystem screens. */
@Composable
fun AeroStatusPill(label: String, accent: Color, modifier: Modifier = Modifier) {
    // Accent changes reflect actual caller state; never pulse during idle/connecting.
    val preferences = LocalVisualPreferences.current
    val reducedMotion = preferences.reducedMotion || preferences.highContrast
    val animatedAccent by animateColorAsState(
        targetValue = accent,
        animationSpec = if (reducedMotion) snap() else tween(
            durationMillis = MichiMotion.durationStandard,
            easing = MichiMotion.easingDecelerate,
        ),
        label = "aero_status_accent",
    )
    Row(
        modifier = modifier
            .background(animatedAccent.copy(alpha = 0.12f), CircleShape)
            .border(0.65.dp, animatedAccent.copy(alpha = 0.25f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.size(6.dp).background(animatedAccent, CircleShape))
        AeroObservedText(
            value = label,
            color = animatedAccent,
            style = MichiTypography.metadata.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}

@Composable
fun AeroInfoPanel(
    overline: String,
    title: String,
    detail: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    AeroSurface(
        role = AeroSurfaceRole.Raised,
        finish = AeroFinish.Anodized,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        borderStroke = BorderStroke(0.65.dp, GlassBorderLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(overline.uppercase(), style = MichiTypography.screenEyebrow, color = accent)
            Text(title, style = MichiTypography.sectionTitle, color = PureWhite,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MichiTypography.metadata, color = TextSecondary)
            content()
        }
    }
}
