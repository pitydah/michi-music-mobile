package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.TextSecondary

/** Only controls layout, not playlist identity, count, playback or persistence. */
internal fun playlistCollectionHeaderStacks(widthDp: Float, fontScale: Float): Boolean =
    widthDp < 332f || fontScale >= 1.5f

/**
 * The hero already contains playlist identity and track count. This lightweight
 * section introduces the list and keeps its presentation action reachable at
 * 320 dp / large fonts without making another card or truncating the heading.
 */
@Composable
internal fun PlaylistCollectionHeader(
    density: PlaylistTrackDensity,
    onToggleDensity: () -> Unit,
    modifier: Modifier = Modifier,
    compactHeight: Boolean = false,
) {
    val scale = LocalDensity.current.fontScale
    val actionTint = if (LocalVisualPreferences.current.highContrast) PureWhite else TextSecondary
    val actionLabel = when (density) {
        PlaylistTrackDensity.COMPACT -> "Ampliar vista"
        PlaylistTrackDensity.EXPANDED -> "Compactar vista"
    }
    BoxWithConstraints(modifier.fillMaxWidth().padding(
        top = if (compactHeight) 10.dp else 18.dp,
        bottom = if (compactHeight) 4.dp else 8.dp,
    ).testTag("playlist_detail_collection_header")) {
        // Keep the editorial stacked policy at normal heights. On a short
        // viewport at 100-130% font, a single compact row preserves the list
        // viewport without truncating either label.
        val stacked = playlistCollectionHeaderStacks(maxWidth.value, scale) &&
            !(compactHeight && scale < 1.5f)
        val title: @Composable () -> Unit = {
            Text("Canciones", color = PureWhite, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
                    .testTag("playlist_detail_collection_title"))
        }
        val densityAction: @Composable () -> Unit = {
            TextButton(
                onClick = onToggleDensity,
                modifier = Modifier.heightIn(min = 48.dp)
                    .semantics { stateDescription = if (density == PlaylistTrackDensity.COMPACT)
                        "Vista compacta" else "Vista ampliada" }
                    .testTag("playlist_detail_density_toggle"),
            ) {
                Icon(Icons.Filled.Tune, contentDescription = null,
                    tint = actionTint, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(actionLabel, color = actionTint,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        if (stacked) {
            Column(modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                title()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    densityAction()
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                title()
                densityAction()
            }
        }
    }
}
