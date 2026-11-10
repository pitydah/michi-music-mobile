package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassFillMid
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.PureWhite

private fun albumViewModeIcon(mode: LibraryViewMode) = when (mode) {
    LibraryViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
    LibraryViewMode.MOSAIC -> Icons.Default.GridView
    LibraryViewMode.COVERFLOW -> Icons.Default.ViewCarousel
    LibraryViewMode.CARDS -> Icons.Default.Style
}

/** Direct, accessible 4-mode picker: no need to tap a cycle button repeatedly. */
@Composable
internal fun AlbumViewModeToggle(
    viewMode: LibraryViewMode,
    onViewModeChange: (LibraryViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val highContrast = LocalVisualPreferences.current.highContrast
    val menuShape = RoundedCornerShape(18.dp)
    Box(modifier = modifier) {
        MichiIconAction(
            icon = albumViewModeIcon(viewMode),
            contentDescription = "Elegir vista de álbumes. Actual: ${albumViewModeLabel(viewMode)}",
            onClick = { expanded = true },
            tint = if (viewMode == LibraryViewMode.CARDS || viewMode == LibraryViewMode.COVERFLOW)
                PrimaryPinkContainer else PureWhite,
            iconSize = 22.dp,
            minTargetSize = MichiSpacing.minTouchTarget,
            testTag = "library_view_mode_toggle",
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.clip(menuShape).background(SurfaceObsidian)
                .border(if (highContrast) 1.5.dp else 0.8.dp,
                    if (highContrast) PureWhite else GlassBorderHigh, menuShape),
        ) {
            listOf(
                LibraryViewMode.MOSAIC,
                LibraryViewMode.COVERFLOW,
                LibraryViewMode.CARDS,
                LibraryViewMode.LIST,
            ).forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            albumViewModeLabel(mode).replaceFirstChar { it.uppercase() },
                            style = MichiTypography.cardTitle,
                            color = if (viewMode == mode) PrimaryPinkContainer else PureWhite,
                        )
                    },
                    onClick = {
                        expanded = false
                        onViewModeChange(mode)
                    },
                    leadingIcon = {
                        Icon(albumViewModeIcon(mode), contentDescription = null,
                            tint = if (viewMode == mode) PrimaryPinkContainer else PureWhite)
                    },
                    trailingIcon = if (viewMode == mode) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = PrimaryPinkContainer) }
                    } else null,
                    modifier = Modifier.background(
                            if (viewMode == mode) GlassFillMid else SurfaceObsidian,
                            RoundedCornerShape(10.dp),
                        ).semantics { selected = viewMode == mode }
                        .testTag("album_mode_option_${mode.name.lowercase()}"),
                )
            }
        }
    }
}

/** Artists keep circular mosaic by default and get a single-tap Cards alternative. */
@Composable
internal fun ArtistViewModeToggle(
    cardsMode: Boolean,
    onCardsModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    MichiIconAction(
        icon = if (cardsMode) Icons.Default.GridView else Icons.Default.Style,
        contentDescription = if (cardsMode) "Vista tarjetas de artistas. Cambiar a mosaico"
            else "Vista mosaico de artistas. Cambiar a tarjetas",
        onClick = { onCardsModeChange(!cardsMode) },
        modifier = modifier,
        tint = if (cardsMode) PrimaryPinkContainer else PureWhite,
        iconSize = 22.dp,
        minTargetSize = MichiSpacing.minTouchTarget,
        testTag = "artist_view_mode_toggle",
    )
}

/** Exhaustive presentation switch: every mode must have a real UI content slot. */
@Composable
internal fun AlbumPresentationContent(
    viewMode: LibraryViewMode,
    mosaicContent: @Composable () -> Unit,
    coverFlowContent: @Composable () -> Unit,
    listContent: @Composable () -> Unit,
    cardsContent: @Composable () -> Unit,
) {
    when (viewMode) {
        LibraryViewMode.MOSAIC -> mosaicContent()
        LibraryViewMode.COVERFLOW -> coverFlowContent()
        LibraryViewMode.LIST -> listContent()
        LibraryViewMode.CARDS -> cardsContent()
    }
}
