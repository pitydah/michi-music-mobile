package org.michimusic.mobile.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataArtworkLyricsProbe
import org.michimusic.core.metadata.MetadataArtworkReplacementPreview
import org.michimusic.core.metadata.MetadataDescribedTextWorkbench
import org.michimusic.core.metadata.MetadataId3DescribedTagCodec
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.TertiaryCyan

/** In-memory session. State belongs to the workbench, so changing tabs loses no proposals. */
internal data class MetadataArtLyricsUiState(
    val load: Load = Load.NotRequested,
    val operations: Map<MetadataDescribedTextWorkbench.Identity,MetadataDescribedTextWorkbench.Change> = emptyMap(),
    val artwork: MetadataArtworkReplacementPreview.Proposal? = null,
    val selectedOrdinal: Int? = null,
) {
    sealed interface Load {
        data object NotRequested:Load
        data object Loading:Load
        data class Ready(val snapshot:MetadataArtworkLyricsProbe.Snapshot):Load
        data class Blocked(val message:String):Load
    }
    val isDirty: Boolean get() = operations.isNotEmpty() || artwork != null
    val inspected: MetadataArtworkLyricsProbe.Snapshot? get() = (load as? Load.Ready)?.snapshot
    val textResult:MetadataDescribedTextWorkbench.Result? get() = inspected?.let { original ->
        MetadataDescribedTextWorkbench.review(true,original.texts,operations.values.toList())
    }
    fun replace(id:MetadataDescribedTextWorkbench.Identity, text:String):MetadataArtLyricsUiState {
        val original = inspected?.texts?.singleOrNull { it.identity == id }?.text ?: return this
        val updated = operations.toMutableMap()
        if (text == original) updated.remove(id) else updated[id] = MetadataDescribedTextWorkbench.Change.Replace(id,text)
        return copy(operations=updated)
    }
    fun clear(id:MetadataDescribedTextWorkbench.Identity):MetadataArtLyricsUiState {
        if (inspected?.texts?.none {it.identity==id} != false) return this
        return copy(operations=operations + (id to MetadataDescribedTextWorkbench.Change.Clear(id)))
    }
    fun keep(id:MetadataDescribedTextWorkbench.Identity):MetadataArtLyricsUiState =
        copy(operations=operations-id)
    fun reset():MetadataArtLyricsUiState = copy(operations=emptyMap(),artwork=null)
}

/** One section of the existing six-tab workbench; no new screen and no source write. */
@Composable
internal fun MichiMetadataArtworkLyricsSection(
    sourceUri:String,
    state:MetadataArtLyricsUiState,
    onChange:(MetadataArtLyricsUiState)->Unit,
    canInspect:Boolean,
    modifier:Modifier=Modifier,
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var message by remember(sourceUri) { mutableStateOf<String?>(null) }
    var pickedOrdinal by remember(sourceUri) { mutableStateOf<Int?>(null) }
    var visibleTextEntries by remember(sourceUri) { mutableStateOf(8) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target=pickedOrdinal
        pickedOrdinal=null
        if(uri!=null && target!=null) scope.launch {
            val bytes=MetadataArtLyricsOnDemandReader.readPickedImage(context.contentResolver,uri)
            if(bytes==null) {message="La imagen es ilegible o excede 8 MiB";return@launch}
            val isJpeg=bytes.size>=4 && bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte()
            val mime=if(isJpeg) "image/jpeg" else "image/png"
            val decodable=withContext(Dispatchers.Default) {
                val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                if(bounds.outWidth !in 1..12000 || bounds.outHeight !in 1..12000) false else {
                    var sample=1
                    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>320) sample*=2
                    val options=BitmapFactory.Options().apply {inSampleSize=sample
                        inPreferredConfig=android.graphics.Bitmap.Config.RGB_565}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?.let {
                        it.recycle();true
                    } ?: false
                }
            }
            if(!decodable) {
                message="La imagen no se pudo decodificar de forma segura";return@launch
            }
            when(val candidate=MetadataArtworkReplacementPreview.review(state.inspected,target,bytes,mime)) {
                is MetadataArtworkReplacementPreview.Result.Ready -> { message=null;onChange(state.copy(artwork=candidate.proposal)) }
                is MetadataArtworkReplacementPreview.Result.Blocked -> message="Propuesta rechazada: ${candidate.reason.name}"
            }
        }
    }
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(MichiSpacing.md)) {
        Text("Inspección de arte y entradas de texto",style=MaterialTheme.typography.titleMedium)
        Text("Se leen únicamente los metadatos del documento local cuando lo solicitas. " +
            "Las imágenes y letras no se guardan ni se suben a ningún servicio.",
            style=MaterialTheme.typography.bodySmall,color=TextSecondary)
        when(val loaded=state.load) {
            MetadataArtLyricsUiState.Load.NotRequested -> Button(
                enabled=canInspect,
                onClick={
                    onChange(state.copy(load=MetadataArtLyricsUiState.Load.Loading))
                    scope.launch {
                        val result=MetadataArtLyricsOnDemandReader.inspect(context.contentResolver,sourceUri)
                        onChange(when(result) {
                            is MetadataArtLyricsOnDemandReader.Outcome.Ready -> state.copy(load=MetadataArtLyricsUiState.Load.Ready(result.snapshot),operations=emptyMap(),artwork=null)
                            is MetadataArtLyricsOnDemandReader.Outcome.Blocked -> state.copy(load=MetadataArtLyricsUiState.Load.Blocked(result.label))
                        })
                    }
                }, modifier=Modifier.fillMaxWidth().sizeIn(minHeight=48.dp)
                    .testTag("metadata_inspect_embedded_content")) {
                Text("Inspeccionar carátulas y letras incrustadas")
            }
            MetadataArtLyricsUiState.Load.Loading -> Row(verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(MichiSpacing.sm)) {
                CircularProgressIndicator(modifier=Modifier.height(20.dp))
                Text("Leyendo cabeceras y etiquetas…")
            }
            is MetadataArtLyricsUiState.Load.Blocked -> {
                Text("No se pudo comprobar todo el contenido: ${loaded.message}",color=MaterialTheme.colorScheme.error)
                TextButton(onClick={onChange(state.copy(load=MetadataArtLyricsUiState.Load.NotRequested))}) {Text("Volver a intentar")}
            }
            is MetadataArtLyricsUiState.Load.Ready -> {
                val snapshot=loaded.snapshot
                Text("Observación local · ${snapshot.format} · ${snapshot.artworks.size} imagen(es) · ${snapshot.texts.size} entrada(s) de texto",
                    color=TertiaryCyan,style=MaterialTheme.typography.labelMedium)
                Text("La observación no equivale a permiso de guardado. Imagen y texto original se conservarán hasta certificar el escritor.",
                    color=TextSecondary,style=MaterialTheme.typography.bodySmall)
                snapshot.artworks.forEach { item ->
                    Column(Modifier.fillMaxWidth().background(AeroGraphite,MichiShapes.md)
                        .border(1.dp,AeroHairline,MichiShapes.md).padding(MichiSpacing.md),
                        verticalArrangement=Arrangement.spacedBy(MichiSpacing.sm)) {
                        Text("Imagen ${item.ordinal+1} · ${pictureTypeName(item.info.type)}",style=MaterialTheme.typography.titleSmall)
                        MetadataEmbeddedPicture(item.image,item.info.sha256)
                        Text("${item.info.mime} · ${item.info.bytes / 1024} KiB · SHA-256 ${item.info.sha256.take(12)}…",
                            style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                        TextButton(onClick={pickedOrdinal=item.ordinal; picker.launch("image/*")},
                            modifier=Modifier.sizeIn(minHeight=48.dp).testTag("metadata_replace_picture_${item.ordinal}")) {
                            Text("Elegir sustitución…")
                        }
                        if(state.artwork?.index==item.ordinal) {
                            Text("PROPUESTA · ${state.artwork.proposedMime} · ${state.artwork.proposedSize/1024} KiB",
                                color=TertiaryCyan,style=MaterialTheme.typography.labelMedium)
                            MetadataEmbeddedPicture(state.artwork.proposedBytes,state.artwork.proposedSha256)
                            Text("La sustitución existe solo en memoria; no se ha preparado un archivo de salida.",
                                style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                            TextButton(onClick={onChange(state.copy(artwork=null))}) {Text("Descartar propuesta de imagen")}
                        }
                    }
                }
                if(snapshot.artworks.isEmpty()) Text("Sin imágenes incrustadas comprobadas en esta lectura.",
                    color=TextSecondary,style=MaterialTheme.typography.bodySmall)
                if(snapshot.format!=MetadataArtworkLyricsProbe.Format.FLAC) {
                    Text("Comentarios y letras por idioma y descriptor",style=MaterialTheme.typography.titleMedium)
                    Text("Las entradas se editan por identidad exacta; no se fusionan idiomas ni se crean descriptores invisibles.",
                        style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                    snapshot.texts.take(visibleTextEntries).forEach { item ->
                        MetadataTypedTextCard(item,state.operations[item.identity],
                            onText={onChange(state.replace(item.identity,it))},
                            onClear={onChange(state.clear(item.identity))},
                            onKeep={onChange(state.keep(item.identity))})
                    }
                    if(snapshot.texts.size > visibleTextEntries) {
                        TextButton(onClick={visibleTextEntries=(visibleTextEntries+8).coerceAtMost(64)},
                            modifier=Modifier.sizeIn(minHeight=48.dp)) {
                            Text("Mostrar más entradas (${snapshot.texts.size-visibleTextEntries} restantes)")
                        }
                    }
                    if(snapshot.texts.isEmpty()) Text("No hay entradas COMM/USLT comprobadas.",
                        color=TextSecondary,style=MaterialTheme.typography.bodySmall)
                } else Text("Las letras FLAC permanecen en el formulario de etiquetas Vorbis.",
                    color=TextSecondary,style=MaterialTheme.typography.bodySmall)
                if(state.operations.isNotEmpty()) {
                    val review=state.textResult
                    Text(if(review is MetadataDescribedTextWorkbench.Result.Blocked)
                        "Revisión bloqueada: ${review.reason.name}" else "${state.operations.size} propuesta(s) de texto para revisar",
                        color=if(review is MetadataDescribedTextWorkbench.Result.Blocked)
                            MaterialTheme.colorScheme.error else TertiaryCyan)
                    TextButton(onClick={onChange(state.copy(operations=emptyMap()))}) {Text("Restablecer comentarios y letras")}
                }
            }
        }
        if (!canInspect && state.load==MetadataArtLyricsUiState.Load.NotRequested)
            Text("Restablece los cambios de COMENTARIO/LETRAS antes de inspeccionar entradas tipadas.",
                color=TextSecondary,style=MaterialTheme.typography.bodySmall)
        message?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }
}

@Composable
private fun MetadataTypedTextCard(
    item:MetadataDescribedTextWorkbench.Item,
    change:MetadataDescribedTextWorkbench.Change?,
    onText:(String)->Unit, onClear:()->Unit,onKeep:()->Unit,
) {
    var confirmClear by remember(item.identity) { mutableStateOf(false) }
    val deleting=change is MetadataDescribedTextWorkbench.Change.Clear
    val text=(change as? MetadataDescribedTextWorkbench.Change.Replace)?.text ?: item.text
    Column(Modifier.fillMaxWidth().background(AeroGraphite,MichiShapes.md)
        .border(1.dp,AeroHairline,MichiShapes.md).padding(MichiSpacing.md),
        verticalArrangement=Arrangement.spacedBy(MichiSpacing.sm)) {
        Text("${if(item.identity.kind==MetadataId3DescribedTagCodec.Kind.COMMENT) "Comentario" else "Letra"} · ${item.identity.language}",
            style=MaterialTheme.typography.titleSmall)
        Text("Descriptor: ${item.identity.description.ifBlank { "(sin descriptor)" }}",
            color=TextSecondary,style=MaterialTheme.typography.bodySmall)
        if(deleting) Text("Eliminar solo esta entrada — propuesta no guardada",
            color=MaterialTheme.colorScheme.error)
        else OutlinedTextField(value=text,onValueChange=onText,
            label={Text("Contenido de la entrada")},modifier=Modifier.fillMaxWidth()
                .testTag("metadata_described_${item.identity.kind.name}_${item.identity.language}_${item.identity.description}"),
            minLines=if(item.identity.kind==MetadataId3DescribedTagCodec.Kind.LYRICS) 4 else 1,
            maxLines=8,isError=text.isBlank())
        if(text.isBlank() && !deleting) Text("Texto temporalmente vacío; utiliza Eliminar para borrar explícitamente.",
            color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(MichiSpacing.sm)) {
            if(change!=null) TextButton(onClick=onKeep) {Text("Restablecer")}
            TextButton(onClick={confirmClear=!confirmClear}) {Text("Eliminar entrada")}
        }
        if(confirmClear) {
            Text("¿Eliminar únicamente esta entrada de ${item.identity.language}?",color=MaterialTheme.colorScheme.error)
            Row(horizontalArrangement=Arrangement.spacedBy(MichiSpacing.sm)) {
                TextButton(onClick={confirmClear=false}) {Text("Cancelar")}
                TextButton(onClick={onClear();confirmClear=false}) {Text("Confirmar eliminación")}
            }
        }
    }
}

@Composable
private fun MetadataEmbeddedPicture(bytes:ByteArray,digest:String) {
    val bitmap by produceState<ImageBitmap?>(initialValue=null,digest) {
        value=withContext(Dispatchers.Default) {
            try {
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                if(bounds.outWidth !in 1..12000 || bounds.outHeight !in 1..12000) null else {
                    var sample=1
                    while(maxOf(bounds.outWidth,bounds.outHeight)/sample>320) sample*=2
                    val opts=BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=android.graphics.Bitmap.Config.RGB_565}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)?.asImageBitmap()
                }
            } catch (_:Exception){null}
        }
    }
    if(bitmap!=null) Image(bitmap=bitmap!!,contentDescription="Carátula incrustada observada en el documento",
        modifier=Modifier.fillMaxWidth().height(162.dp),contentScale=ContentScale.Fit)
    else Text("Vista previa no decodificable; los bytes originales no se han modificado.",
        color=TextSecondary,style=MaterialTheme.typography.bodySmall)
}
private fun pictureTypeName(type:Int):String=when(type){3->"Portada frontal";4->"Contraportada";6->"Soporte físico";else->"Tipo $type"}
