package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.AeroActionButton
import org.michimusic.mobile.ui.components.AeroActionKind

/**
 * The playlist detail keeps one compact row at every width. The shared
 * [aeroHeroActionsVertical] policy is intentionally untouched: it still serves
 * other Aero surfaces. Extreme widths/fonts reserve the same row with a 48 dp
 * touch target and full accessibility labels.
 */
@Composable
internal fun PlaylistPrimaryActions(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = LocalConfiguration.current
    val compact = playlistPrimaryActionsCompact(config.screenWidthDp.toFloat(), config.fontScale)
    val minHeight = if (compact) 48.dp else 56.dp

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AeroActionButton(
            label = "Reproducir",
            icon = Icons.Filled.PlayArrow,
            kind = AeroActionKind.Primary,
            onClick = onPlay,
            modifier = Modifier
                .weight(0.62f)
                .heightIn(min = minHeight),
            testTag = "playlist_detail_play_all",
            accessibilityLabel = "Reproducir playlist",
        )
        AeroActionButton(
            label = "Mezclar",
            icon = Icons.Filled.Shuffle,
            kind = AeroActionKind.Quiet,
            onClick = onShuffle,
            modifier = Modifier
                .weight(0.38f)
                .heightIn(min = minHeight),
            testTag = "playlist_detail_shuffle_all",
            accessibilityLabel = "Mezclar playlist",
        )
    }
}
