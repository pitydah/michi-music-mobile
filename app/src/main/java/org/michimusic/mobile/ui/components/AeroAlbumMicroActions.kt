package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian

/**
 * UI5 isolated component, intentionally NOT wired to AlbumDetailScreen while UI4 PR #14
 * changes that screen and MichiIconAction. Integration will reuse the existing album
 * playback/playlist callbacks and tags; this component never owns a playback session.
 *
 * Visible geometry: 44/40/40 dp. Touchable geometry: three distinct 48 dp squares.
 * The caller centers this intrinsic-width strip under the album description.
 */
@Composable
internal fun AeroAlbumMicroActions(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAddToPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    playEnabled: Boolean = true,
    shuffleEnabled: Boolean = true,
    addToPlaylistEnabled: Boolean = true,
) {
    Row(
        modifier = modifier.widthIn(min = AeroAlbumMicroMetrics.GROUP_WIDTH_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(AeroAlbumMicroMetrics.GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AeroAlbumMicroAction(
            icon = Icons.Filled.PlayArrow,
            description = "Reproducir álbum",
            enabled = playEnabled,
            primary = true,
            testTag = "album_detail_play",
            onClick = onPlay,
        )
        AeroAlbumMicroAction(
            icon = Icons.Filled.Shuffle,
            description = "Reproducir álbum aleatoriamente",
            enabled = shuffleEnabled,
            primary = false,
            testTag = "album_detail_shuffle",
            onClick = onShuffle,
        )
        AeroAlbumMicroAction(
            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
            description = "Añadir álbum a una lista",
            enabled = addToPlaylistEnabled,
            primary = false,
            testTag = "album_detail_add_to_playlist",
            onClick = onAddToPlaylist,
        )
    }
}

@Composable
private fun AeroAlbumMicroAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    primary: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val visibleSize = if (primary) AeroAlbumMicroMetrics.PLAY_SURFACE_DP.dp
        else AeroAlbumMicroMetrics.SECONDARY_SURFACE_DP.dp
    val glyphSize = if (primary) AeroAlbumMicroMetrics.PLAY_GLYPH_DP.dp
        else AeroAlbumMicroMetrics.SECONDARY_GLYPH_DP.dp

    // This Box is decorative only. MichiIconAction below is the ONLY clickable node,
    // preserving UI4 motion/haptics and the existing album testTag/accessible name.
    Box(
        modifier = Modifier.size(AeroAlbumMicroMetrics.TOUCH_TARGET_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (primary) {
            val coral = if (highContrast) listOf(Color(0xFF28343E), Color(0xFF202B35))
                else listOf(Color(0xFFFF8798), PrimaryPinkContainer)
            Box(
                modifier = Modifier.size(visibleSize)
                    .alpha(if (enabled) 1f else 0.42f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(coral))
                    .border(
                        0.8.dp,
                        if (highContrast) Color.White else Color(0xFFFFACB7).copy(alpha = 0.56f),
                        RoundedCornerShape(12.dp),
                    ),
            )
        } else {
            AeroSurface(
                role = AeroSurfaceRole.Raised,
                modifier = Modifier.size(visibleSize).alpha(if (enabled) 1f else 0.42f),
                shape = RoundedCornerShape(12.dp),
                finish = AeroFinish.Anodized,
            ) {}
        }
        MichiIconAction(
            icon = icon,
            contentDescription = description,
            onClick = onClick,
            enabled = enabled,
            tint = if (primary && !highContrast) SurfaceObsidian else PureWhite,
            iconSize = glyphSize,
            minTargetSize = AeroAlbumMicroMetrics.TOUCH_TARGET_DP.dp,
            testTag = testTag,
        )
    }
}
