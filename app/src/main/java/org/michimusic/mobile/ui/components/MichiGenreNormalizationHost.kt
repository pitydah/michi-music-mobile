package org.michimusic.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.repository.ExtendedTagUnavailableReason
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.mobile.ui.components.premium.MichiBottomSheet
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * NORM-P0-01: per-track actions inside a genre detail.
 *
 * Purely local, user-triggered affordances. The menu never starts playback and
 * never opens the editor by itself: the caller closes this sheet as the
 * transition and then opens the ONE shared editor container.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenreTrackActionsSheet(
    track: Track,
    onDismiss: () -> Unit,
    onNormalize: () -> Unit,
    onEditMetadata: (() -> Unit)? = null,
) {
    MichiBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                .testTag("genre_track_actions"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = PureWhite,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = MichiSpacing.minTouchTarget)
                    .clickable(role = Role.Button, onClick = onNormalize)
                    .testTag("genre_track_action_normalize")
                    .semantics {
                        contentDescription = "Revisar géneros de ${track.title}"
                        role = Role.Button
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.EditNote, contentDescription = null, tint = PureWhite)
                Text("Revisar géneros", color = PureWhite)
            }
            if (onEditMetadata != null && track.source == TrackSource.LOCAL) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)
                        .clickable(role = Role.Button, onClick = onEditMetadata)
                        .testTag("genre_track_action_metadata")
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.EditNote, contentDescription = null, tint = PureWhite)
                    Text("Editar metadatos · previsualización", color = PureWhite)
                }
            }
            Text(
                if (track.source == TrackSource.LOCAL) {
                    "Solo lectura salvo que la escritura de etiquetas esté certificada."
                } else {
                    "Pista remota: únicamente consulta, sin acceso al archivo."
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("genre_track_actions_close"),
            ) { Text("Cerrar") }
        }
    }
}

/**
 * Single editor host for the Genres route. It reads the same verified evidence
 * [TrackExtendedMetadataDetails] exposes to Now Playing, only after the user asked
 * for the editor, and then hands the exact receipt to the shared editor body.
 * Remote tracks never reach repositories or write paths.
 */
@Composable
internal fun MichiGenreNormalizationHost(
    track: Track,
    onDismiss: () -> Unit,
    origin: MichiGenreEditorOrigin = MichiGenreEditorOrigin.GENRES,
    repository: ExtendedTrackMetadataRepository = koinInject(),
) {
    if (track.source != TrackSource.LOCAL) {
        GenreEditorReadOnlyNotice(track, onDismiss)
        return
    }
    // K47-NORM-04: size belongs to the receipt. Same URI with different bytes is a
    // different document and cannot inherit the previous evidence.
    var evidence by remember(track.sourceId, track.id, track.filepath, track.size) {
        mutableStateOf<GenreEvidenceUiState>(GenreEvidenceUiState.Loading)
    }
    LaunchedEffect(repository, track.sourceId, track.id, track.filepath, track.size) {
        evidence = loadGenreEvidence(repository, track)
    }
    when (val current = evidence) {
        GenreEvidenceUiState.Loading -> GenreEditorEvidenceLoading(track, onDismiss)
        is GenreEvidenceUiState.Verified -> MichiGenreNormalizationEditor(
            track = track,
            embeddedGenres = current.genres,
            tagsVerified = true,
            origin = origin,
            onClose = onDismiss,
        )
        // Unavailable is NOT proof that the file has no genre: the editor opens
        // read-only with `tagsVerified = false` and says so explicitly.
        is GenreEvidenceUiState.Unavailable -> MichiGenreNormalizationEditor(
            track = track,
            embeddedGenres = emptyList(),
            tagsVerified = false,
            origin = origin,
            onClose = onDismiss,
            evidenceReason = current.reason,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreEditorEvidenceLoading(track: Track, onDismiss: () -> Unit) {
    MichiBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("mi26_genre_editor_loading"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Revisar géneros",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = PureWhite,
            )
            Text(
                listOf(track.title, track.artist).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            CircularProgressIndicator()
            Text(
                "Leyendo los metadatos verificados de esta canción…",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("mi26_genre_editor_loading_close"),
            ) { Text("Cerrar") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreEditorReadOnlyNotice(track: Track, onDismiss: () -> Unit) {
    MichiBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("mi26_genre_editor_remote"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Revisar géneros",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = PureWhite,
            )
            Text(
                listOf(track.title, track.artist).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Text(
                "Pista remota: esta vista solo puede consultar. No se leen rutas locales " +
                    "ni se habilita ninguna escritura.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("mi26_genre_editor_remote_close"),
            ) { Text("Cerrar") }
        }
    }
}
