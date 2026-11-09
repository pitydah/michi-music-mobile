package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.michimusic.data.repository.ALL_FOLDERS
import org.michimusic.data.repository.MediaFolder
import org.michimusic.data.repository.defaultFolderSelection
import org.michimusic.mobile.ui.components.AeroSurface
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * Folder scope picker. The plain content is deliberately separate from the
 * Dialog and from Koin/MediaStore, so its selection contract can be tested
 * without constructing the application graph.
 */
@Composable
internal fun LibraryFolderDialog(
    folders: List<MediaFolder>,
    initialSelection: Set<String>?,
    onDismiss: () -> Unit,
    onSave: (Set<String>?) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            LibraryFolderDialogContent(
                folders = folders,
                initialSelection = initialSelection,
                onDismiss = onDismiss,
                onSave = onSave,
            )
        }
    }
}

@Composable
internal fun LibraryFolderDialogContent(
    folders: List<MediaFolder>,
    initialSelection: Set<String>?,
    onDismiss: () -> Unit,
    onSave: (Set<String>?) -> Unit,
) {
    val smartSelection = remember(folders) { defaultFolderSelection(folders) }
    var selected by remember(folders, initialSelection) {
        mutableStateOf(initialSelection ?: smartSelection)
    }
    var usesSmartDefault by remember(folders, initialSelection) {
        mutableStateOf(initialSelection == null)
    }
    val allSelected = ALL_FOLDERS in selected
    val saveEnabled = selected.isNotEmpty()

    fun toggle(folder: String) {
        val concrete = if (allSelected) folders.map { it.name }.toSet() else selected
        selected = if (folder in concrete) concrete - folder else concrete + folder
        usesSmartDefault = false
    }

    AeroSurface(
        role = AeroSurfaceRole.Raised,
        finish = AeroFinish.Anodized,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library_folder_dialog"),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Carpetas de la biblioteca",
                color = PureWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Elige qué carpetas del teléfono aparecen como música. " +
                    "Las carpetas con pistas marcadas como música y las carpetas Music/Download " +
                    "se incluyen de forma automática.",
                color = TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = {
                        selected = setOf(ALL_FOLDERS)
                        usesSmartDefault = false
                    },
                    modifier = Modifier.testTag("library_folder_all"),
                ) { Text("Todas") }
                TextButton(
                    onClick = {
                        selected = smartSelection
                        usesSmartDefault = false
                    },
                    modifier = Modifier.testTag("library_folder_music_only"),
                ) { Text("Solo música") }
                TextButton(
                    onClick = {
                        selected = smartSelection
                        usesSmartDefault = true
                    },
                    modifier = Modifier.testTag("library_folder_reset"),
                ) { Text("Restablecer") }
            }

            if (folders.isEmpty()) {
                Text(
                    text = "No se encontraron carpetas de audio. Concede acceso a la biblioteca " +
                        "o importa archivos desde Ajustes.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(folders, key = { it.name }) { folder ->
                        val checked = allSelected || folder.name in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("library_folder_${folder.name}")
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { toggle(folder.name) },
                                modifier = Modifier.testTag("library_folder_check_${folder.name}"),
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(
                                    text = folder.name,
                                    color = PureWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = buildString {
                                        append("${folder.trackCount} pista(s)")
                                        if (folder.musicTrackCount > 0) {
                                            append(" · ${folder.musicTrackCount} de música")
                                        }
                                    },
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("library_folder_cancel"),
                ) { Text("Cancelar") }
                TextButton(
                    onClick = { onSave(if (usesSmartDefault) null else selected) },
                    enabled = saveEnabled,
                    modifier = Modifier
                        .testTag("library_folder_save")
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        text = "Guardar",
                        color = if (saveEnabled) TertiaryCyan else TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
