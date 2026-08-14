package org.michimusic.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.TertiaryCyan

data class NavTabItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val defaultNavTabs = listOf(
    NavTabItem("home", "Inicio", Icons.Filled.Home, Icons.Outlined.Home),
    NavTabItem("library", "Biblioteca", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
    NavTabItem("nowplaying", "Ahora", Icons.Filled.PlayCircle, Icons.Outlined.PlayCircle),
    NavTabItem("remote", "Remoto", Icons.Filled.SettingsRemote, Icons.Outlined.SettingsRemote),
    NavTabItem("sync", "Sync", Icons.Filled.Sync, Icons.Outlined.Sync),
    NavTabItem("settings", "Ajustes", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun BottomNavBar(
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<NavTabItem> = defaultNavTabs,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(GlassFillHigh)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(GlassBorderHigh, Color(0x11FFFFFF)),
                ),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            )
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val isSelected = currentRoute == tab.route
                val itemColor by animateColorAsState(
                    targetValue = if (isSelected) TertiaryCyan else OnSurfaceVariant.copy(alpha = 0.65f),
                    label = "tab_color",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 24.dp),
                            onClick = { onTabSelected(tab.route) },
                        )
                        .testTag("nav_tab_${tab.route}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = if (isSelected) {
                                Modifier
                                    .size(34.dp)
                                    .drawBehind {
                                        drawCircle(
                                            color = TertiaryCyan.copy(alpha = 0.20f),
                                            radius = 17.dp.toPx(),
                                        )
                                    }
                            } else {
                                Modifier.size(34.dp)
                            },
                        ) {
                            Icon(
                                imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.label,
                                tint = itemColor,
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = tab.label,
                            color = itemColor,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
