package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.premium.MichiBottomSheet
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary

/** Deliberately no deletion or file-system action. A snapshot is captured by the caller. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryHideConfirmationSheet(
    collectionLabel: String,
    trackCount: Int,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    MichiBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
                .testTag("library_hide_collection_confirm"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Quitar $collectionLabel de la biblioteca", color = PureWhite,
                style = MaterialTheme.typography.titleLarge)
            val quantity = if (trackCount == 1) "1 canción" else "$trackCount canciones"
            Text(
                "Se ocultarán $quantity de esta biblioteca de Michi. " +
                    "Los archivos originales permanecerán en tu dispositivo. " +
                    "Podrás restaurarlos desde Ajustes → Biblioteca y almacenamiento → Canciones ocultas.",
                color = TextSecondary, style = MaterialTheme.typography.bodyMedium,
            )
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("library_hide_collection_error"))
            // Vertical actions stay readable at 320dp / 200% font scaling.
            AeroActionButton(
                label = if (busy) "Guardando…" else "Quitar de Michi",
                onClick = onConfirm, enabled = !busy && trackCount > 0,
                kind = AeroActionKind.Primary,
                modifier = Modifier.fillMaxWidth(), testTag = "library_hide_collection_commit",
            )
            TextButton(onClick = onDismiss, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Cancelar", color = TextSecondary)
            }
        }
    }
}
