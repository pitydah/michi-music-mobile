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
import org.michimusic.data.repository.MusicRoot
import org.michimusic.data.repository.ExistingGenreVerificationReport
import org.michimusic.mobile.library.LibraryStorageSnapshot
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import androidx.compose.material3.TextButton
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary






/** Pure UI: no SAF launchers, repository calls, IO, or worker scheduling.
 * The controller owns grants and domain operations; the view only issues events.
 */
@Composable
internal fun SettingsLibraryDetails(
    currentRoot: MusicRoot,
    busy: Boolean,
    progress: Int,
    message: String?,
    permissionRevoked: Boolean,
    permissionError: Boolean,
    permissionReady: Boolean,
    canWrite: Boolean,
    lastScanText: String?,
    snapshot: LibraryStorageSnapshot?,
    onSelectTree: () -> Unit,
    onRestoreDefault: () -> Unit,
    onRescan: () -> Unit,
    onRecoverGenres: () -> Unit,
    onVerifyGenres: () -> Unit,
    verificationReport: ExistingGenreVerificationReport?,
    canCancelGenreOperation: Boolean,
    onCancelGenreOperation: () -> Unit,
    onRefreshStorage: () -> Unit,
    onGrantWrite: () -> Unit,
    onCreateFolder: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSectionCard(
            icon = Icons.Default.FolderSpecial,
            title = "Biblioteca & Almacenamiento",
            subtitle = "Carpeta de música, carátulas y sincronización",
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
                    text = message!!,
                    style = MaterialTheme.typography.bodySmall.copy(color = TertiaryCyan),
                )
            }


            SettingsLibraryStorageMetrics(
                snapshot = snapshot,
                context = context,
                onRefresh = onRefreshStorage,
            )

            lastScanText?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted),
                    modifier = Modifier.testTag("settings_library_last_scan"),
                )
            }


            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    enabled = !busy && !currentRoot.isDefault,
                    onClick = onRestoreDefault,
                    modifier = Modifier.testTag("settings_library_restore_music"),
                ) {
                    Text("Restaurar Music/")
                }
                TextButton(
                    enabled = !busy,
                    onClick = onRescan,
                    modifier = Modifier.testTag("settings_library_rescan"),
                ) {
                    Text("Volver a escanear")
                }
            }
            TextButton(
                enabled = !busy && (currentRoot.isDefault || !permissionRevoked),
                onClick = onRecoverGenres,
                modifier = Modifier.testTag("settings_library_recover_genres"),
            ) {
                Text("Recuperar géneros faltantes")
            }
            Text(
                "Relee etiquetas de archivos de esta biblioteca solo cuando lo solicitas; " +
                    "no modifica la música ni activa escaneos permanentes.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            SettingsGenreVerificationDetails(
                enabled = !busy && (currentRoot.isDefault || !permissionRevoked),
                busy = busy,
                canCancel = canCancelGenreOperation,
                report = verificationReport,
                onVerify = onVerifyGenres,
                onCancel = onCancelGenreOperation,
            )


            Spacer(Modifier.height(12.dp))


            SettingsLibraryAccessControls(
                currentRoot = currentRoot,
                busy = busy || !permissionReady,
                canWrite = canWrite,
                onGrantWrite = onGrantWrite,
                onCreateFolder = onCreateFolder,
            )

            Spacer(Modifier.height(12.dp))
            org.michimusic.mobile.ui.components.HiddenSongsSection()
            Spacer(Modifier.height(12.dp))


            Text("ESCANEO Y CARÁTULAS", style = MaterialTheme.typography.labelSmall,
                color = TertiaryCyan)
            Text(
                "El escaneo manual está disponible aquí. " +
                    "El escaneo al iniciar y la descarga automática de carátulas " +
                    "no están habilitados: requieren coordinadores específicos.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.testTag("settings_library_unconnected_options"),
            )


            Spacer(Modifier.height(12.dp))



        }
    }
}
