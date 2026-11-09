package org.michimusic.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.motion.aeroDockSelectionEmphasis
import org.michimusic.mobile.ui.motion.aeroDockPanelOpacity
import org.michimusic.mobile.ui.motion.aeroDockBorderOpacity
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import org.michimusic.mobile.R

/**
 * A dock destination. The icon is a single vector resource: the selected and
 * unselected states are expressed by tint and label weight, so a second
 * "filled" variant would only duplicate the same geometry.
 */
data class NavTabItem(
    val route: String,
    val label: String,
    @DrawableRes val iconRes: Int,
)

/**
 * Phosphor Icons (MIT) for the five dock destinations. Geometry comes straight
 * from phosphoricons.com at the 256 viewport: no hand-drawn Canvas glyph remains
 * and the Wi-Fi mark identifies Devices without tracking signal strength.
 */
val defaultNavTabs = listOf(
    NavTabItem("home", "Inicio", R.drawable.ic_dock_home),
    NavTabItem("library", "Biblioteca", R.drawable.ic_dock_library),
    NavTabItem("playlist", "Playlists", R.drawable.ic_dock_playlists),
    NavTabItem("search", "Buscar", R.drawable.ic_dock_search),
    NavTabItem("devices", "Dispositivos", R.drawable.ic_dock_devices),
)

internal enum class DockLayout { SINGLE_ROW, TWO_BY_TWO }

internal fun dockLayout(screenWidthDp: Int, fontScale: Float): DockLayout =
    if (screenWidthDp <= 412 && fontScale.isFinite() && fontScale >= 1.7f) {
        DockLayout.TWO_BY_TWO
    } else {
        DockLayout.SINGLE_ROW
    }

// A five-item dock needs a two-row fallback BEFORE labels shrink below a usable
// size. Keep the pre-UI5 four-item policy unchanged for compatible call sites.
internal fun dockLayoutForTabCount(screenWidthDp: Int, fontScale: Float, tabCount: Int): DockLayout =
    if (tabCount >= 5 && (screenWidthDp <= 520 || !fontScale.isFinite() || fontScale >= 1.3f)) {
        DockLayout.TWO_BY_TWO // legacy name: 3+2 when the fifth tab is present
    } else dockLayout(screenWidthDp, fontScale)

@Composable
fun BottomNavBar(
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<NavTabItem> = defaultNavTabs,
    selectedRoute: String? = currentRoute,
) {
    val configuration = LocalConfiguration.current
    val layout = dockLayoutForTabCount(configuration.screenWidthDp, configuration.fontScale, tabs.size)
    val dockHeight = if (layout == DockLayout.TWO_BY_TWO) {
        if (tabs.size >= 5 && configuration.fontScale >= 1.7f) 190.dp else 144.dp
    } else aeroDockHeightDp(configuration.fontScale).dp
    val highContrast = org.michimusic.mobile.ui.theme.LocalVisualPreferences.current.highContrast
    // Native Compose equivalent of a fixed, responsive bottom glass navigation.
    // This is not a WebView: HTML/CSS cannot style the Android navigation surface.
    val outerPadding = if (configuration.screenWidthDp <= 340) 10.dp else 16.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = outerPadding, vertical = 6.dp)
            .testTag("bottom_nav_bar"),
        contentAlignment = Alignment.Center,
    ) {
        AeroSurface(
            role = AeroSurfaceRole.Floating,
            finish = AeroFinish.Smoked,
            shape = MichiShapes.lg,
            modifier = Modifier.fillMaxWidth().height(dockHeight),
            contentAlignment = Alignment.Center,
        ) {
            if (layout == DockLayout.TWO_BY_TWO) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    // Keep five tabs on two lines rather than 2+2+1, which would
                    // shrink the third row below the minimum accessible touch size.
                    tabs.chunked(if (tabs.size >= 5) 3 else 2).forEach { rowTabs ->
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            rowTabs.forEach { tab ->
                                DockTab(
                                    tab = tab,
                                    isSelected = selectedRoute == tab.route,
                                    highContrast = highContrast,
                                    onTabSelected = onTabSelected,
                                    modifier = Modifier.weight(1f).padding(2.dp),
                                    preserveFullLabelSize = true,
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = if (configuration.screenWidthDp <= 340) 4.dp else 8.dp,
                        vertical = 4.dp,
                    ),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEach { tab ->
                        DockTab(
                            tab = tab,
                            isSelected = selectedRoute == tab.route,
                            highContrast = highContrast,
                            onTabSelected = onTabSelected,
                            modifier = Modifier.weight(1f).height(dockHeight - 16.dp),
                            preserveFullLabelSize = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DockTab(
    tab: NavTabItem,
    isSelected: Boolean,
    highContrast: Boolean,
    onTabSelected: (String) -> Unit,
    modifier: Modifier,
    preserveFullLabelSize: Boolean,
) {
    val reducedMotion = org.michimusic.mobile.ui.theme.LocalVisualPreferences.current.reducedMotion
    val emphasis = aeroDockSelectionEmphasis(isSelected)
    val itemColor by animateColorAsState(
        targetValue = when {
            isSelected && highContrast -> PureWhite
            isSelected -> PrimaryPinkContainer
            highContrast -> PureWhite
            else -> PureWhite.copy(alpha = 0.82f)
        },
        animationSpec = if (reducedMotion) androidx.compose.animation.core.snap()
        else androidx.compose.animation.core.tween(durationMillis = 180),
        label = "tab_color",
    )
    Box(
        modifier = modifier
            .clip(MichiShapes.md)
            .background(PrimaryPinkContainer.copy(alpha = aeroDockPanelOpacity(emphasis, highContrast)))
            .border(
                width = if (highContrast) 1.dp else 0.65.dp,
                color = PrimaryPinkContainer.copy(alpha = aeroDockBorderOpacity(emphasis, highContrast)),
                shape = MichiShapes.md,
            )
            .semantics(mergeDescendants = true) {
                selected = isSelected
                stateDescription = if (isSelected) "Sección actual" else "Otra sección"
                contentDescription = tab.label
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = PrimaryPinkContainer),
                role = Role.Tab,
                onClick = { if (!isSelected) onTabSelected(tab.route) },
            )
            .testTag("nav_tab_${tab.route}"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(width = 24.dp, height = 2.dp).clip(RoundedCornerShape(2.dp))
                    .background(itemColor.copy(alpha = emphasis)),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Icon(
                painter = painterResource(tab.iconRes),
                contentDescription = null,
                tint = itemColor,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            BasicText(
                text = tab.label,
                modifier = Modifier.testTag("nav_label_${tab.route}"),
                maxLines = if (preserveFullLabelSize) 2 else 1,
                softWrap = preserveFullLabelSize,
                overflow = TextOverflow.Ellipsis,
                autoSize = if (preserveFullLabelSize) null else TextAutoSize.StepBased(
                    minFontSize = 7.sp,
                    maxFontSize = 11.sp,
                    stepSize = 0.5.sp,
                ),
                style = TextStyle(
                    color = if (isSelected || highContrast) PureWhite else PureWhite.copy(alpha = 0.82f),
                    fontSize = 11.sp,
                    letterSpacing = 0.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                ),
            )
        }
    }
}
