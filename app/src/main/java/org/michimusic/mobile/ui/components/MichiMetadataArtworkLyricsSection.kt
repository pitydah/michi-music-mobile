package org.michimusic.mobile.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import org.michimusic.mobile.ui.theme.PureWhite
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
    onInspect:()->Unit={},
    modifier:Modifier=Modifier,
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var message by remember(sourceUri) { mutableStateOf<String?>(null) }
    var pickedOrdinal by remember(sourceUri) { mutableStateOf<Int?>(null) }
    var visibleTextEntries by remember(sourceUri) { mutableStateOf(8) }
    var showTypedEntries by remember(sourceUri) { mutableStateOf(false) }
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
                is MetadataArtworkReplacementPreview.Result.Blocked -> message="No se pudo usar esa imagen (${candidate.reason.name.lowercase()})."
            }
        }
    }
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(MichiSpacing.md)) {
        when(val loaded=state.load) {
            MetadataArtLyricsUiState.Load.NotRequested,
            MetadataArtLyricsUiState.Load.Loading -> Row(verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(MichiSpacing.sm)) {
                CircularProgressIndicator(modifier=Modifier.height(20.dp))
                Text("Leyendo carátulas y letras…",color=TextSecondary)
            }
            is MetadataArtLyricsUiState.Load.Blocked -> {
                Text("No se pudo leer el contenido incrustado: ${loaded.message}",
                    color=MaterialTheme.colorScheme.error)
                TextButton(onClick=onInspect,modifier=Modifier.sizeIn(minHeight=48.dp)) {
                    Text("Volver a intentar")
                }
            }
            is MetadataArtLyricsUiState.Load.Ready -> {
                val snapshot=loaded.snapshot
                val front=snapshot.artworks.firstOrNull{it.info.type==3} ?: snapshot.artworks.firstOrNull()
                if(front!=null) {
                    MetadataEmbeddedPicture(front.image,front.info.sha256,height=200.dp)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("Carátula incrustada",style=MaterialTheme.typography.bodySmall,
                            color=TextSecondary,modifier=Modifier.weight(1f))
                        TextButton(
                            onClick={pickedOrdinal=front.ordinal;picker.launch("image/*")},
                            modifier=Modifier.sizeIn(minHeight=48.dp)
                                .testTag("metadata_change_artwork"),
                        ) { Text("Cambiar carátula") }
                    }
                    if(snapshot.artworks.size>1) Text(
                        "${snapshot.artworks.size} imágenes incrustadas; se muestra la principal.",
                        style=MaterialTheme.typography.bodySmall,color=TextSecondary)
                    if(state.artwork?.index==front.ordinal) {
                        Text("Nueva carátula seleccionada",color=TertiaryCyan,
                            style=MaterialTheme.typography.labelMedium)
                        MetadataEmbeddedPicture(state.artwork.proposedBytes,
                            state.artwork.proposedSha256,height=140.dp)
                        TextButton(onClick={onChange(state.copy(artwork=null))},
                            modifier=Modifier.sizeIn(minHeight=48.dp)) {Text("Descartar propuesta")}
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(160.dp).background(AeroGraphite,MichiShapes.md)
                        .border(1.dp,AeroHairline,MichiShapes.md),
                        contentAlignment=Alignment.Center) {
                        Text("Sin carátula incrustada verificada",color=TextSecondary)
                    }
                }
                if(snapshot.texts.isNotEmpty()) {
                    TextButton(
                        onClick={showTypedEntries=!showTypedEntries},
                        modifier=Modifier.sizeIn(minHeight=48.dp)
                            .testTag("metadata_typed_text_toggle"),
                    ) {
                        Text(if(showTypedEntries) "Ocultar otras entradas de texto"
                            else "Otras entradas de texto (${snapshot.texts.size})")
                    }
                    if(showTypedEntries) {
                        if(snapshot.format!=MetadataArtworkLyricsProbe.Format.FLAC) {
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
                        } else Text("Las letras FLAC permanecen en el formulario de etiquetas.",
                            color=TextSecondary,style=MaterialTheme.typography.bodySmall)
                    }
                }
                if(state.operations.isNotEmpty()) {
                    val review=state.textResult
                    Text(if(review is MetadataDescribedTextWorkbench.Result.Blocked)
                        "Hay entradas de texto que necesitan corrección."
                        else "${state.operations.size} propuesta(s) de texto",
                        color=if(review is MetadataDescribedTextWorkbench.Result.Blocked)
                            MaterialTheme.colorScheme.error else TertiaryCyan)
                    TextButton(onClick={onChange(state.copy(operations=emptyMap()))},
                        modifier=Modifier.sizeIn(minHeight=48.dp)) {Text("Restablecer comentarios y letras")}
                }
            }
        }
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
            style=MaterialTheme.typography.titleSmall,color=PureWhite)
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
private fun MetadataEmbeddedPicture(bytes:ByteArray,digest:String,height:androidx.compose.ui.unit.Dp=162.dp) {
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
        modifier=Modifier.fillMaxWidth().height(height),contentScale=ContentScale.Fit)
    else Text("Vista previa no decodificable; los bytes originales no se han modificado.",
        color=TextSecondary,style=MaterialTheme.typography.bodySmall)
}
private fun pictureTypeName(type:Int):String=when(type){3->"Portada frontal";4->"Contraportada";6->"Soporte físico";else->"Tipo $type"}
