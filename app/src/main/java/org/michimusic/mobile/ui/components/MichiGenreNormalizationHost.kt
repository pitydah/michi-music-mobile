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
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
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

private sealed interface GenreEditorEvidenceState {
    data object Loading : GenreEditorEvidenceState
    data class Available(val genres: List<String>) : GenreEditorEvidenceState
    data object Unavailable : GenreEditorEvidenceState
    data object Error : GenreEditorEvidenceState
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
    var evidence by remember(track.sourceId, track.id, track.filepath) {
        mutableStateOf<GenreEditorEvidenceState>(GenreEditorEvidenceState.Loading)
    }
    LaunchedEffect(repository, track.sourceId, track.id, track.filepath) {
        evidence = try {
            val verified = repository.loadVerified(
                track.sourceId, track.id, track.filepath, track.size,
            )
            if (verified == null || verified.sourceId != track.sourceId ||
                verified.trackId != track.id
            ) {
                GenreEditorEvidenceState.Unavailable
            } else {
                GenreEditorEvidenceState.Available(verified.genres)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GenreEditorEvidenceState.Error
        }
    }
    when (val current = evidence) {
        GenreEditorEvidenceState.Loading -> GenreEditorEvidenceLoading(track, onDismiss)
        is GenreEditorEvidenceState.Available -> MichiGenreNormalizationEditor(
            track = track,
            embeddedGenres = current.genres,
            tagsVerified = true,
            origin = origin,
            onClose = onDismiss,
        )
        // Unavailable is NOT proof that the file has no genre: the editor opens
        // read-only with `tagsVerified = false` and says so explicitly.
        GenreEditorEvidenceState.Unavailable,
        GenreEditorEvidenceState.Error,
        -> MichiGenreNormalizationEditor(
            track = track,
            embeddedGenres = emptyList(),
            tagsVerified = false,
            origin = origin,
            onClose = onDismiss,
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
