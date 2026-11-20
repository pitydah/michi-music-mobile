package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.michimusic.data.repository.MusicRoot
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted

/**
 * The root-folder card of Settings → Library. It stays a pure view: the
 * controller owns SAF grants, scans and domain operations; this composable
 * only renders the current root, its permission truth and the select action.
 */
@Composable
internal fun SettingsLibraryRootSection(
    currentRoot: MusicRoot,
    busy: Boolean,
    progress: Int,
    message: String?,
    permissionRevoked: Boolean,
    permissionError: Boolean,
    permissionReady: Boolean,
    onSelectTree: () -> Unit,
) {
    SettingsSectionCard(
        icon = Icons.Default.FolderSpecial,
        title = "Carpeta de música",
        subtitle = "Ubicación seleccionada y permisos actuales",
        accent = TertiaryCyan,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(GlassFillLow)
                .border(1.dp, GlassBorderLow, RoundedCornerShape(12.dp))
                .clickable(enabled = !busy) {
                    onSelectTree()
                }
                .padding(14.dp)
                .testTag("settings_library_root"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Carpeta de música",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = PureWhite,
                    ),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        busy -> "Actualizando biblioteca… ${progress.coerceAtLeast(0)} pista(s)"
                        else -> currentRoot.label
                    },
                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TertiaryCyan,
                modifier = Modifier.size(20.dp),
            )
        }

        if (!permissionReady) {
            Text("Comprobando permisos de esta carpeta…",
                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted))
        }
        if (permissionError) {
            Text("No se pudo verificar el permiso de esta carpeta. Escritura deshabilitada.",
                style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFFFB4AB)),
                modifier = Modifier.testTag("settings_library_permission_unknown"))
        }
        if (permissionRevoked) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "El permiso de esta carpeta se revocó. Vuelve a elegirla para recuperar el acceso.",
                style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFFFB4AB)),
            )
        }
        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall.copy(color = TertiaryCyan),
            )
        }
    }
}
