package org.michimusic.mobile.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.data.repository.ExtendedTrackMetadataRepository
import org.michimusic.mobile.ui.components.premium.MichiBottomSheet

/** One Michi modal. A dirty draft cannot be dismissed silently by a drag or Back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MichiMetadataEditorPreviewSheet(
    track: Track,
    onDismiss: () -> Unit,
    repository: ExtendedTrackMetadataRepository = koinInject(),
) {
    var hasDraft by remember(track.sourceId, track.id, track.filepath) { mutableStateOf(false) }
    var confirmExit by remember(track.sourceId, track.id, track.filepath) { mutableStateOf(false) }
    val requestExit = {
        if (hasDraft) confirmExit = true else onDismiss()
    }
    MichiBottomSheet(onDismissRequest = requestExit) {
        MichiMetadataEditorPreviewEntry(track = track, repository = repository,
            onDismiss = requestExit, onDirtyChanged = { hasDraft = it })
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("¿Descartar el borrador?") },
            text = { Text("Hay cambios sin guardar. Cerrar descartará únicamente la propuesta; " +
                "el archivo musical permanecerá intacto.") },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; onDismiss() }) {
                    Text("Descartar cambios")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("Seguir editando") }
            },
        )
    }
}
