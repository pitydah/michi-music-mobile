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
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary

/** Composed ONLY after the user expands the item. No I/O during list scrolling. */
@Composable
internal fun TrackExtendedMetadataDetails(
    track: Track,
    repository: ExtendedTrackMetadataRepository = koinInject(),
) {
    var state by remember(track.sourceId, track.id, track.filepath, track.size) {
        mutableStateOf<GenreEvidenceUiState>(GenreEvidenceUiState.Loading)
    }
    LaunchedEffect(repository, track.sourceId, track.id, track.filepath, track.size) {
        state = GenreEvidenceUiState.Loading
        state = loadGenreEvidence(repository, track)
    }
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("track_extended_metadata_details"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (val current = state) {
            GenreEvidenceUiState.Loading -> CircularProgressIndicator()
            is GenreEvidenceUiState.Verified -> {
                TrackExtendedMetadataPolicy.rows(current.metadata).forEach { row ->
                    Text(row.label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Text(row.value, style = MaterialTheme.typography.bodyMedium, color = PureWhite)
                }
                Text("Metadatos cotejados con la etiqueta actual del archivo.",
                    style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                MichiGenreNormalizationAction(track, current.genres, tagsVerified = true)
            }
            // K48-S4: the same verdict and the same translated reason the Genres
            // route shows. An unreadable tag is never presented as an empty one.
            is GenreEvidenceUiState.Unavailable -> {
                Text("Etiqueta original: aún no verificable.", color = TextSecondary)
                Text(
                    evidenceUnavailableLabel(current.reason),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.testTag("track_extended_metadata_reason"),
                )
                MichiGenreNormalizationAction(track, emptyList(), tagsVerified = false)
            }
        }
    }
}
