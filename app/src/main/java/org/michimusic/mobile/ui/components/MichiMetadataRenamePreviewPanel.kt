package org.michimusic.mobile.ui.components

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataFilenameToolkit
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * User-triggered rename PREVIEW. Reads only the current display name, never
 * copies, moves, renames or assumes that SAF/MediaStore supports atomic rename.
 * This is a real Compose surface, not a nonfunctional "save" button.
 */
@Composable
internal fun MichiMetadataRenamePreviewPanel(snapshot: MetadataSnapshot) {
    val resolver=LocalContext.current.contentResolver
    val uri=snapshot.target.uri
    var template by remember(uri) { mutableStateOf("%track% - %title%") }
    var readRequest by remember(uri) { mutableIntStateOf(0) }
    var originalName by remember(uri) { mutableStateOf<String?>(null) }
    var reason by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri, readRequest) {
        if(readRequest==0)return@LaunchedEffect
        val name=try {withContext(Dispatchers.IO) {
            if(!uri.startsWith("content://"))null else
                resolver.query(Uri.parse(uri),arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,null,null)?.use { c ->
                    val column=c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if(column<0 || !c.moveToFirst() || c.isNull(column)) null
                    else c.getString(column)?.takeIf { it.length in 1..255 }
                }
        }} catch (cancel:CancellationException) {throw cancel}
          catch (_:Exception) {null}
        originalName=name
        reason=if(name==null)"El proveedor no permite verificar el nombre actual." else null
    }
    Column(Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Renombrar desde etiquetas",style=MaterialTheme.typography.titleMedium)
        Text("Vista previa sin mover ni renombrar archivos. Solo usa etiquetas verificadas.",
            style=MaterialTheme.typography.bodySmall,color=TextSecondary)
        OutlinedTextField(value=template,onValueChange={if(it.length<=240) template=it},
            label={Text("Plantilla")},singleLine=true,modifier=Modifier.fillMaxWidth()
                .testTag("metadata_rename_template"))
        Text("Ejemplo: %track% - %artist% - %title%",
            style=MaterialTheme.typography.labelSmall,color=TextSecondary)
        TextButton(onClick={ readRequest++ }, modifier=Modifier.testTag("metadata_rename_inspect")) {
            Text("Leer nombre y previsualizar")
        }
        reason?.let {Text(it,color=MaterialTheme.colorScheme.error,
            style=MaterialTheme.typography.bodySmall)}
        originalName?.let { current ->
            when(val proposed=MetadataFilenameToolkit.proposeRename(snapshot,current,template)){
                is MetadataFilenameToolkit.Result.Success -> {
                    Text("Actual: ${proposed.value.before}",style=MaterialTheme.typography.bodySmall)
                    Text("Propuesto: ${proposed.value.after}",style=MaterialTheme.typography.bodyMedium)
                    Text("No aplicado. Falta comprobar colisiones y certificar el renombrado en el proveedor.",
                        style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                }
                is MetadataFilenameToolkit.Result.Rejected -> Text(
                    "No puede proponerse este nombre: ${proposed.reason.name}",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            }
        }
    }
}
