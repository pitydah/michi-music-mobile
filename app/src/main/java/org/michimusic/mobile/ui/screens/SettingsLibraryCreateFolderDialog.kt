package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.michimusic.data.repository.normalizedMusicFolderName
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary

/** Presentational dialog only. The library authority owns the actual write operation. */
@Composable
internal fun SettingsLibraryCreateFolderDialog(
    rootLabel: String,
    folderBusy: Boolean,
    writeAllowed: Boolean,
    name: String,
    error: String?,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val valid = normalizedMusicFolderName(name) != null
    AlertDialog(
        onDismissRequest = { if (!folderBusy) onDismiss() },
        title = { Text("Nueva carpeta musical") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Se creará dentro de $rootLabel. No se moverá ni borrará música existente.")
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Nombre de carpeta") },
                    singleLine = true,
                    isError = name.isNotBlank() && !valid,
                    modifier = Modifier.fillMaxWidth().testTag("settings_library_folder_name"),
                )
                error?.let { Text(it, color = Color(0xFFFFB4AB),
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !folderBusy && writeAllowed && valid, onClick = onConfirm,
                modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("settings_library_confirm_create_folder")) { Text("Crear") }
        },
        dismissButton = {
            TextButton(enabled = !folderBusy, onClick = onDismiss) { Text("Cancelar") }
        },
        containerColor = SurfaceObsidian,
        titleContentColor = PureWhite,
        textContentColor = TextSecondary,
        shape = RoundedCornerShape(20.dp),
    )
}
