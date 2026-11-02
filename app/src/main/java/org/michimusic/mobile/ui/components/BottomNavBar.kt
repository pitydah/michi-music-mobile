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
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite

private enum class DockGlyph { Home, Library, Search, Devices }

// Preserve the NavTabItem public contract for app navigation and integrations.
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
private fun MichiDockGlyph(
    glyph: DockGlyph,
    tint: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val stroke = if (selected) 2.05.dp else 1.85.dp
    Canvas(modifier = modifier.size(22.dp)) {
        val sw = stroke.toPx()
        when (glyph) {
            DockGlyph.Home -> {
                val roof = Path().apply {
                    moveTo(size.width * 0.18f, size.height * 0.46f)
                    lineTo(size.width * 0.5f, size.height * 0.18f)
                    lineTo(size.width * 0.82f, size.height * 0.46f)
                }
                drawPath(roof, tint, style = Stroke(width = sw, cap = StrokeCap.Round))
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.28f, size.height * 0.46f),
                    size = Size(size.width * 0.44f, size.height * 0.30f),
                    cornerRadius = CornerRadius(size.width * 0.06f),
                    style = Stroke(width = sw)
                )
            }
            DockGlyph.Library -> {
                // Album spine and an unmistakable musical note, not four radiator bars.
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * .19f, size.height * .14f),
                    size = Size(size.width * .62f, size.height * .72f),
                    cornerRadius = CornerRadius(size.width * .08f),
                    style = Stroke(width = sw),
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * .32f, size.height * .18f),
                    end = Offset(size.width * .32f, size.height * .82f),
                    strokeWidth = sw * .8f,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * .54f, size.height * .36f),
                    end = Offset(size.width * .67f, size.height * .31f),
                    strokeWidth = sw * 1.1f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * .54f, size.height * .36f),
                    end = Offset(size.width * .54f, size.height * .60f),
                    strokeWidth = sw * 1.1f,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = tint,
                    radius = size.width * .07f,
                    center = Offset(size.width * .48f, size.height * .62f),
                )
            }
            DockGlyph.Search -> {
                drawCircle(
                    color = tint,
                    radius = size.minDimension * 0.22f,
                    center = Offset(size.width * 0.44f, size.height * 0.42f),
                    style = Stroke(width = sw)
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.58f, size.height * 0.57f),
                    end = Offset(size.width * 0.76f, size.height * 0.75f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
            }
            DockGlyph.Devices -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(size.width * 0.24f, size.height * 0.18f),
                    size = Size(size.width * 0.52f, size.height * 0.42f),
                    cornerRadius = CornerRadius(size.width * 0.06f),
                    style = Stroke(width = sw)
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.40f, size.height * 0.68f),
                    end = Offset(size.width * 0.60f, size.height * 0.68f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.50f, size.height * 0.60f),
                    end = Offset(size.width * 0.50f, size.height * 0.68f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.32f, size.height * 0.78f),
                    end = Offset(size.width * 0.68f, size.height * 0.78f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
fun BottomNavBar(
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<NavTabItem> = defaultNavTabs,
    selectedRoute: String? = currentRoute,
) {
    val configuration = LocalConfiguration.current
    // At 200% font scale, the two-line "Dispositivos" label needs real layout
    // height; scaling text into a fixed 88dp dock silently clips the second line.
    val dockHeight = (aeroDockHeightDp(configuration.fontScale) +
        if (configuration.fontScale >= 1.7f) 20 else 0).dp
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(dockHeight)
                .clip(MichiShapes.lg)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF25252F),
                            Color(0xFF1D1E27),
                            AeroGraphite,
                        ),
                    ),
                )
                .border(
                    width = 0.85.dp,
                    color = Color.White.copy(alpha = if (highContrast) 0.86f else 0.21f),
                    shape = MichiShapes.lg,
                )
                .padding(horizontal = if (configuration.screenWidthDp <= 340) 4.dp else 8.dp, vertical = 4.dp),
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
                        targetValue = when {
                            isSelected && highContrast -> PureWhite
                            isSelected -> PrimaryPinkContainer
                            highContrast -> PureWhite
                            else -> PureWhite.copy(alpha = 0.82f)
                        },
                        animationSpec = if (reducedMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(durationMillis = 180),
                        label = "tab_color",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(dockHeight - 16.dp)
                            .clip(MichiShapes.md)
                            // Selected section remains legible without animation or color alone.
                            .background(
                                if (isSelected) PrimaryPinkContainer.copy(alpha = if (highContrast) 0.22f else 0.10f)
                                else Color.Transparent,
                            )
                            .border(
                                width = if (highContrast) 1.dp else 0.65.dp,
                                color = if (isSelected) PrimaryPinkContainer.copy(alpha = if (highContrast) 0.9f else 0.32f)
                                    else Color.Transparent,
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
                                onClick = { if (selectedRoute != tab.route) onTabSelected(tab.route) },
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
                                    .size(width = 24.dp, height = 2.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (isSelected) itemColor else Color.Transparent),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val glyph = when (tab.route) {
                                "home" -> DockGlyph.Home
                                "library" -> DockGlyph.Library
                                "search" -> DockGlyph.Search
                                "devices" -> DockGlyph.Devices
                                else -> null
                            }
                            if (glyph != null) {
                                MichiDockGlyph(glyph = glyph, tint = itemColor, selected = isSelected)
                            } else {
                                Icon(
                                    imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = null,
                                    tint = itemColor,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            // DOCK-01: at large font scales a single-word label such as
                            // "Dispositivos" cannot wrap, so a fixed 11.sp size ellipsized it
                            // inside 73.dp-wide tabs. Auto-size keeps the whole label by
                            // shrinking only as much as the available width requires, instead of
                            // truncating it for the users who explicitly asked for bigger text.
                            BasicText(
                                text = tab.label,
                                maxLines = if (dockHeight > 64.dp) 2 else 1,
                                overflow = TextOverflow.Ellipsis,
                                autoSize = TextAutoSize.StepBased(
                                    minFontSize = 9.sp,
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
            }
        }
    }
}
