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
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextMuted

data class NavTabItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

val defaultNavTabs = listOf(
    NavTabItem("home", "Inicio", Icons.Filled.Home, Icons.Outlined.Home),
    NavTabItem("library", "Biblioteca", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
    NavTabItem("search", "Buscar", Icons.Filled.Search, Icons.Outlined.Search),
    NavTabItem("devices", "Dispositivos", Icons.Filled.Devices, Icons.Outlined.Devices),
)

@Composable
fun BottomNavBar(
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<NavTabItem> = defaultNavTabs,
    selectedRoute: String? = currentRoute,
) {
    val dockHeight = aeroDockHeightDp(LocalConfiguration.current.fontScale).dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("bottom_nav_bar"),
        contentAlignment = Alignment.Center,
    ) {
        // Floating Dock
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dockHeight)
                .clip(MichiShapes.lg)
                .background(AeroGraphiteRaised)
                .border(
                    width = 0.75.dp,
                    color = AeroHairline,
                    shape = MichiShapes.lg,
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val isSelected = selectedRoute == tab.route

                    val reducedMotion = org.michimusic.mobile.ui.theme.LocalVisualPreferences.current.reducedMotion
                    val itemColor by animateColorAsState(
                        targetValue = if (isSelected) PrimaryPinkContainer else TextMuted,
                        animationSpec = if (reducedMotion) androidx.compose.animation.core.snap()
                            else androidx.compose.animation.core.tween(durationMillis = 180),
                        label = "tab_color",
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(dockHeight - 16.dp)
                            .clip(MichiShapes.md)
                            .semantics(mergeDescendants = true) {
                                selected = isSelected
                                stateDescription = if (isSelected) "Sección actual" else "Otra sección"
                                contentDescription = tab.label
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true, color = PrimaryPinkContainer),
                                role = Role.Tab,
                                onClick = { if (currentRoute != tab.route) onTabSelected(tab.route) },
                            )
                            .testTag("nav_tab_${tab.route}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(width = 20.dp, height = 2.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (isSelected) PrimaryPinkContainer else Color.Transparent),
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Icon(
                                imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = null,
                                tint = itemColor,
                                modifier = Modifier.size(20.dp),
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Text(
                                text = tab.label,
                                color = if (isSelected) PureWhite else TextMuted,
                                fontSize = 11.sp,
                                letterSpacing = 0.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = if (dockHeight > 64.dp) 2 else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
