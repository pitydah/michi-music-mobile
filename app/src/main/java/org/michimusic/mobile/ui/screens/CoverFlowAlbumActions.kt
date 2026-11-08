package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.AeroActionButton
import org.michimusic.mobile.ui.components.AeroActionKind
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

@Composable
internal fun CoverFlowAlbumActions(
    albumTitle: String,
    hasPlayableTracks: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAddToPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (LocalConfiguration.current.fontScale >= 1.6f) {
            MichiIconAction(
                Icons.Default.PlayArrow,
                "Reproducir $albumTitle",
                onClick = onPlay,
                enabled = hasPlayableTracks,
                tint = PureWhite,
                iconSize = 26.dp,
                testTag = "coverflow_play_album",
            )
        } else {
            AeroActionButton(
                "Reproducir",
                onClick = onPlay,
                icon = Icons.Default.PlayArrow,
                kind = AeroActionKind.Primary,
                enabled = hasPlayableTracks,
                testTag = "coverflow_play_album",
            )
        }
        MichiIconAction(
            Icons.Default.Shuffle,
            "Reproducir aleatorio $albumTitle",
            onClick = onShuffle,
            enabled = hasPlayableTracks,
            tint = TertiaryCyan,
            iconSize = 22.dp,
            testTag = "coverflow_shuffle_album",
        )
        MichiIconAction(
            Icons.Default.Add,
            "Añadir $albumTitle a playlist",
            onClick = onAddToPlaylist,
            enabled = hasPlayableTracks,
            tint = TextSecondary,
            iconSize = 22.dp,
            testTag = "coverflow_add_to_playlist",
        )
    }
}
