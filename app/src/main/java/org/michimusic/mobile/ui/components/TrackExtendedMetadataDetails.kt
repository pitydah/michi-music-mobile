package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary

private sealed interface ExtendedDetailsState {
    data object Loading : ExtendedDetailsState
    data class Available(val rows: List<TrackExtendedMetadataRow>) : ExtendedDetailsState
    data object Unavailable : ExtendedDetailsState
    data object Error : ExtendedDetailsState
}

/** Composed ONLY after the user expands the item. No I/O during list scrolling. */
@Composable
internal fun TrackExtendedMetadataDetails(
    track: Track,
    repository: ExtendedTrackMetadataRepository = koinInject(),
) {
    var state by remember(track.sourceId, track.id, track.filepath) {
        mutableStateOf<ExtendedDetailsState>(ExtendedDetailsState.Loading)
    }
    LaunchedEffect(repository, track.sourceId, track.id, track.filepath) {
        state = ExtendedDetailsState.Loading
        state = try {
            val evidence = repository.loadVerified(track.sourceId, track.id, track.filepath, track.size)
            if (evidence == null || evidence.sourceId != track.sourceId ||
                evidence.trackId != track.id) ExtendedDetailsState.Unavailable
            else TrackExtendedMetadataPolicy.rows(evidence)
                .takeIf { it.isNotEmpty() }
                ?.let(ExtendedDetailsState::Available) ?: ExtendedDetailsState.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ExtendedDetailsState.Error
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("track_extended_metadata_details"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (val current = state) {
            ExtendedDetailsState.Loading -> CircularProgressIndicator()
            is ExtendedDetailsState.Available -> {
                current.rows.forEach { row ->
                    Text(row.label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Text(row.value, style = MaterialTheme.typography.bodyMedium, color = PureWhite)
                }
                Text("Metadatos cotejados con la etiqueta actual del archivo.",
                    style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            ExtendedDetailsState.Unavailable -> Text(
                "No hay metadatos extendidos verificados para esta canción.", color = TextSecondary)
            ExtendedDetailsState.Error -> Text(
                "No se pudieron consultar los metadatos de la biblioteca.", color = TextSecondary)
        }
    }
}
