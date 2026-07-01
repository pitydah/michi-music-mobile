package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.GlassBg
import org.michimusic.mobile.ui.theme.GlassBorder
import org.michimusic.mobile.ui.theme.MichiRadius
import org.michimusic.mobile.ui.theme.MichiSize
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.TextMuted

data class MichiNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun MichiBottomNavigation(
    items: List<MichiNavItem>,
    currentRoute: String?,
    onItemClick: (MichiNavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(SurfaceElevated),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(MichiRadius.pill))
                .background(GlassBg)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val isSelected = currentRoute == item.route ||
                    (currentRoute != null && currentRoute.startsWith(item.route.removeSuffix("/")) && item.route != "home")
                val tint = if (isSelected) AccentPink else TextMuted

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(MichiRadius.small))
                        .then(
                            if (isSelected) {
                                Modifier.background(AccentPink.copy(alpha = 0.10f))
                            } else Modifier
                        )
                        .clickable { onItemClick(item) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(MichiSize.iconMedium),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = tint,
                    )
                }
            }
        }
    }
}
