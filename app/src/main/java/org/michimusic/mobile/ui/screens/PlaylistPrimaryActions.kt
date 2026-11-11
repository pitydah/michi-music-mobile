package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.AeroActionButton
import org.michimusic.mobile.ui.components.AeroActionKind
import org.michimusic.mobile.ui.components.aeroHeroActionsVertical

/**
 * Same visual hierarchy as the canonical Aero hero: primary playback first,
 * quieter shuffle second. At narrow widths or fontScale >= 1.25, stack full
 * width touch targets. No new queue logic or state is created here.
 */
@Composable
internal fun PlaylistPrimaryActions(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = LocalConfiguration.current
    val stacked = aeroHeroActionsVertical(config.screenWidthDp, config.fontScale)
    val play: @Composable (Modifier) -> Unit = { child ->
        AeroActionButton(
            label = "Reproducir", icon = Icons.Filled.PlayArrow,
            kind = AeroActionKind.Primary,
            onClick = onPlay,
            modifier = child.heightIn(min = 52.dp).testTag("playlist_detail_play_all"),
        )
    }
    val shuffle: @Composable (Modifier) -> Unit = { child ->
        AeroActionButton(
            label = "Mezclar", icon = Icons.Filled.Shuffle,
            kind = AeroActionKind.Quiet,
            onClick = onShuffle,
            modifier = child.heightIn(min = 52.dp).testTag("playlist_detail_shuffle_all"),
        )
    }
    if (stacked) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            play(Modifier.fillMaxWidth())
            shuffle(Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            play(Modifier.weight(0.6f))
            shuffle(Modifier.weight(0.4f))
        }
    }
}
