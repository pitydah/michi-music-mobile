package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.michimusic.core.intelligence.MichiIntelligencePolicy
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OutlineVariant
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceContainer
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * K47-MI-01/MI-02: stable, compact intelligence rail for the genre detail.
 *
 * It is not a second screen and it never owns playback: the host resolves a
 * snapshot once and hands immutable picks here. Collapsed it occupies the
 * 72-80 dp lane of the contract instead of a full card; expanded it shows at
 * most four picks and the row that is playing stays in place.
 */
internal data class GenreIntelligencePick(
    val key: MichiIntelligencePolicy.TrackKey,
    val title: String,
    val reason: String,
)

internal data class GenreIntelligenceUiState(
    val genreTitle: String,
    val expanded: Boolean,
    val picks: List<GenreIntelligencePick>,
    val playingKey: MichiIntelligencePolicy.TrackKey? = null,
    val warning: String? = null,
    val reasonDetail: String? = null,
)

/**
 * K48-S3: the rail may only name a pick as playing while the player is truly
 * playing it. Deriving the key from the current track alone left a row saying
 * "Reproduciendo" after the user paused or the queue stopped.
 */
internal fun genreIntelligencePlayingKey(
    currentTrack: Track?,
    isPlaying: Boolean,
    picks: List<GenreIntelligencePick>,
): MichiIntelligencePolicy.TrackKey? {
    if (!isPlaying) return null
    val track = currentTrack ?: return null
    val key = MichiIntelligencePolicy.TrackKey(track.sourceId, track.id)
    return picks.firstOrNull { it.key == key }?.key
}

private val RailShape = RoundedCornerShape(16.dp)

@Composable
internal fun GenreIntelligenceRail(
    state: GenreIntelligenceUiState,
    onToggleExpanded: () -> Unit,
    onPlayPick: (MichiIntelligencePolicy.TrackKey) -> Unit,
    onPlaySelection: () -> Unit,
    onShowReason: (MichiIntelligencePolicy.TrackKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RailShape)
            .background(SurfaceContainer, RailShape)
            .border(1.dp, OutlineVariant, RailShape)
            .testTag("genre_intelligence_section"),
    ) {
        if (state.expanded) {
            ExpandedHeader(state, onToggleExpanded)
            state.picks.forEachIndexed { index, pick ->
                RailPickRow(
                    pick = pick,
                    index = index,
                    playing = pick.key == state.playingKey,
                    onPlayPick = onPlayPick,
                    onShowReason = onShowReason,
                )
            }
            if (state.picks.size > 1) {
                if (state.playingKey != null) {
                    // The mix is already playing. Restarting it from here would be an
                    // unannounced playback change, so the control becomes informational.
                    Text(
                        "Reproduciendo selección (${state.picks.size})",
                        color = TertiaryCyan,
                        style = MichiTypography.metadata,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MichiSpacing.minTouchTarget)
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                            .testTag("genre_intelligence_play_mix"),
                    )
                } else {
                    TextButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = MichiSpacing.minTouchTarget)
                            .padding(horizontal = 12.dp)
                            .testTag("genre_intelligence_play_mix"),
                        onClick = onPlaySelection,
                    ) { Text("Reproducir selección (${state.picks.size})") }
                }
            }
            Text(
                state.reasonDetail
                    ?: "Basado en afinidades verificadas de tu biblioteca · solo en este dispositivo",
                style = MichiTypography.microLabel,
                color = TextSecondary,
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                    .testTag("genre_intelligence_reason"),
            )
        } else {
            CollapsedLane(state, onToggleExpanded)
        }
        state.warning?.let {
            Text(
                it,
                color = PrimaryPinkContainer,
                style = MichiTypography.metadata,
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                    .testTag("genre_intelligence_warning"),
            )
        }
    }
}

@Composable
private fun CollapsedLane(
    state: GenreIntelligenceUiState,
    onToggleExpanded: () -> Unit,
) {
    val count = state.picks.size
    val headline = if (count == 1) "Para ti · 1 sugerencia" else "Para ti · $count sugerencias"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp, max = 80.dp)
            .clickable(onClickLabel = "Mostrar sugerencias", onClick = onToggleExpanded)
            .padding(start = 12.dp, end = 4.dp)
            .testTag("genre_intelligence_collapsed"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = PrimaryPinkContainer,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                headline,
                color = PureWhite,
                style = MichiTypography.metadata.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("genre_intelligence_title"),
            )
            Text(
                "Basado en tus escuchas",
                color = TextSecondary,
                style = MichiTypography.microLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            modifier = Modifier
                .size(MichiSpacing.minTouchTarget)
                .testTag("genre_intelligence_expand"),
            onClick = onToggleExpanded,
        ) {
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = "Mostrar sugerencias",
                tint = TextSecondary,
            )
        }
    }
}

@Composable
private fun ExpandedHeader(
    state: GenreIntelligenceUiState,
    onToggleExpanded: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 8.dp)
            .testTag("genre_intelligence_expanded"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Para ti en ${state.genreTitle}",
                style = MichiTypography.metadata.copy(fontWeight = FontWeight.Bold),
                color = PureWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("genre_intelligence_title"),
            )
            Text(
                "Basado en tus escuchas",
                style = MichiTypography.microLabel,
                color = TextSecondary,
            )
        }
        TextButton(
            modifier = Modifier
                .heightIn(min = MichiSpacing.minTouchTarget)
                .testTag("genre_intelligence_collapse"),
            onClick = onToggleExpanded,
        ) {
            Icon(Icons.Filled.ExpandLess, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Ocultar", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun RailPickRow(
    pick: GenreIntelligencePick,
    index: Int,
    playing: Boolean,
    onPlayPick: (MichiIntelligencePolicy.TrackKey) -> Unit,
    onShowReason: (MichiIntelligencePolicy.TrackKey) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp, max = 64.dp)
            .padding(start = 12.dp, end = 4.dp)
            .testTag("genre_intelligence_track_$index"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = MichiSpacing.minTouchTarget)
                .clickable(onClickLabel = "Por qué se sugiere") { onShowReason(pick.key) }
                .padding(vertical = 6.dp)
                .testTag("genre_intelligence_track_reason_$index")
                .semantics {
                    contentDescription = "Por qué se sugiere ${pick.title}"
                },
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                pick.title,
                color = PureWhite,
                style = MichiTypography.metadata.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (playing) "Reproduciendo · ${pick.reason}" else pick.reason,
                color = if (playing) TertiaryCyan else TextSecondary,
                style = MichiTypography.microLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (playing) {
            // This pick is the one sounding right now. The row states the fact and
            // offers no control, because restarting it would surface a fake pause.
            Box(
                modifier = Modifier
                    .size(MichiSpacing.minTouchTarget)
                    .testTag("genre_intelligence_track_play_$index")
                    .semantics {
                        stateDescription = "Reproduciendo"
                        contentDescription = "Reproduciendo ${pick.title}"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = TertiaryCyan,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            IconButton(
                modifier = Modifier
                    .size(MichiSpacing.minTouchTarget)
                    .testTag("genre_intelligence_track_play_$index")
                    .semantics { stateDescription = "Pausado" },
                onClick = { onPlayPick(pick.key) },
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Reproducir ${pick.title}",
                    tint = PureWhite,
                )
            }
        }
    }
}
