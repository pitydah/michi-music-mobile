package org.michimusic.mobile.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite

@Composable
internal fun AlbumViewModeToggle(
    viewMode: LibraryViewMode,
    onViewModeChange: (LibraryViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nextMode = nextAlbumViewMode(viewMode)
    MichiIconAction(
        icon = when (nextMode) {
            LibraryViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
            LibraryViewMode.MOSAIC -> Icons.Default.GridView
            LibraryViewMode.COVERFLOW -> Icons.Default.ViewCarousel
        },
        contentDescription = "Vista ${albumViewModeLabel(viewMode)}. Cambiar a ${albumViewModeLabel(nextMode)}",
        onClick = { onViewModeChange(nextMode) },
        modifier = modifier,
        tint = if (viewMode == LibraryViewMode.COVERFLOW) PrimaryPinkContainer else PureWhite,
        iconSize = 22.dp,
        minTargetSize = MichiSpacing.minTouchTarget,
        testTag = "library_view_mode_toggle",
    )
}

/** Exhaustive presentation switch: every visible mode requires a real content slot. */
@Composable
internal fun AlbumPresentationContent(
    viewMode: LibraryViewMode,
    mosaicContent: @Composable () -> Unit,
    coverFlowContent: @Composable () -> Unit,
    listContent: @Composable () -> Unit,
) {
    when (viewMode) {
        LibraryViewMode.MOSAIC -> mosaicContent()
        LibraryViewMode.COVERFLOW -> coverFlowContent()
        LibraryViewMode.LIST -> listContent()
    }
}
