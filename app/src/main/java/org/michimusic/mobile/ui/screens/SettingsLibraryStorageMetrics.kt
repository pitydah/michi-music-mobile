package org.michimusic.mobile.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.library.LibraryStorageSnapshot
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.TextMuted

/** Snapshot is private app-volume data, NEVER a measurement of an arbitrary SAF tree. */
@Composable
internal fun SettingsLibraryStorageMetrics(
    snapshot: LibraryStorageSnapshot?, context: Context, onRefresh: () -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Text("ESPACIO UTILIZADO POR MICHI LINK", style = MaterialTheme.typography.labelSmall,
        color = TertiaryCyan)
    Text(snapshot?.let { info ->
        val bytes = info.syncedMusicBytes
        val count = info.syncedFileCount
        if (bytes != null && count != null) {
            "Archivos locales de Michi Link: " +
                android.text.format.Formatter.formatShortFileSize(context, bytes) +
                " · $count archivo(s), incluidos temporales"
        } else "Archivos de Michi Link: sin comprobar"
    } ?: "Archivos de Michi Link: sin comprobar",
        color = TextSecondary, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("settings_michi_sync_storage_used"))
    Text(snapshot?.appVolumeAvailableBytes?.let { available ->
        "Libre en el volumen de la app: " +
            android.text.format.Formatter.formatShortFileSize(context, available)
    } ?: "Espacio libre del volumen: no disponible",
        color = TextMuted, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("settings_michi_sync_storage_free"))
    TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)
        .testTag("settings_michi_sync_storage_refresh")) { Text("Actualizar espacio") }
    Text("Estos datos corresponden al almacenamiento privado de Michi Link; " +
        "no representan el tamaño ni el espacio libre de la carpeta SAF elegida.",
        style = MaterialTheme.typography.bodySmall, color = TextMuted)
}
