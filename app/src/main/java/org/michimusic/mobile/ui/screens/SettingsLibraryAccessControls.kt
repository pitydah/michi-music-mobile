package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.michimusic.data.repository.MusicRoot
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/** Visible permission truth: WRITE is an explicit opt-in for the exact active SAF root. */
@Composable
internal fun SettingsLibraryAccessControls(
    currentRoot: MusicRoot,
    busy: Boolean,
    canWrite: Boolean,
    onGrantWrite: () -> Unit,
    onCreateFolder: () -> Unit,
) {
    Text("ACCESO A CARPETAS", style = MaterialTheme.typography.labelSmall, color = TertiaryCyan)
    Text(text = when {
        currentRoot.isDefault ->
            "Music/ se lee mediante MediaStore. Para crear carpetas, elige un árbol SAF y autoriza escritura."
        canWrite -> "Lectura y escritura autorizadas. Solo se crean subcarpetas si lo solicitas."
        else -> "Solo lectura. Crear carpetas requiere una autorización adicional."
    }, style = MaterialTheme.typography.bodySmall, color = TextSecondary,
        modifier = Modifier.testTag("settings_library_access_status"))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!currentRoot.isDefault && !canWrite) {
            TextButton(enabled = !busy, onClick = onGrantWrite,
                modifier = Modifier.heightIn(min = 48.dp).testTag("settings_library_grant_write")) {
                Text("Autorizar escritura")
            }
        }
        TextButton(enabled = !busy && !currentRoot.isDefault && canWrite,
            onClick = onCreateFolder,
            modifier = Modifier.heightIn(min = 48.dp).testTag("settings_library_create_folder")) {
            Icon(Icons.Default.CreateNewFolder, contentDescription = null,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.padding(horizontal = 3.dp))
            Text("Nueva carpeta")
        }
    }
}
