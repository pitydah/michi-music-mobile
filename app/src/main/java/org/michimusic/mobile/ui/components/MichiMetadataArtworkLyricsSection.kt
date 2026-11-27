package org.michimusic.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.core.metadata.MetadataArtworkLyricsProbe
import org.michimusic.core.metadata.MetadataArtworkReplacementPreview
import org.michimusic.core.metadata.MetadataDescribedTextWorkbench
import org.michimusic.core.metadata.MetadataId3DescribedTagCodec
import org.michimusic.mobile.ui.components.premium.AeroFloatingSurface
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
    val artworkSource: ArtworkSource? = null,
    val artworkEdit: ArtworkEdit? = null,
    val selectedOrdinal: Int? = null,
) {
    sealed interface Load {
        data object NotRequested:Load
        data object Loading:Load
        data class Ready(val snapshot:MetadataArtworkLyricsProbe.Snapshot):Load
        data class Blocked(val message:String):Load
    }
    val isDirty: Boolean get() = operations.isNotEmpty() || artwork != null || artworkEdit != null
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
    /** Proposes a NEW entry only for an identity the verified snapshot proves absent. */
    fun add(id:MetadataDescribedTextWorkbench.Identity, text:String):MetadataArtLyricsUiState {
        val snapshot = inspected ?: return this
        if (snapshot.texts.any { it.identity == id }) return this
        return copy(operations=operations + (id to MetadataDescribedTextWorkbench.Change.Add(id,text)))
    }
    fun updateDraft(id:MetadataDescribedTextWorkbench.Identity, text:String):MetadataArtLyricsUiState {
        val change = operations[id] ?: return this
        if (change is MetadataDescribedTextWorkbench.Change.Add && text.isBlank())
            return copy(operations=operations-id)
        val next = when(change) {
            is MetadataDescribedTextWorkbench.Change.Add ->
                MetadataDescribedTextWorkbench.Change.Add(id,text)
            is MetadataDescribedTextWorkbench.Change.Replace ->
                MetadataDescribedTextWorkbench.Change.Replace(id,text)
            is MetadataDescribedTextWorkbench.Change.Clear -> change
        }
        return copy(operations=operations + (id to next))
    }
    fun clear(id:MetadataDescribedTextWorkbench.Identity):MetadataArtLyricsUiState {
        if (inspected?.texts?.none {it.identity==id} != false) return this
        return copy(operations=operations + (id to MetadataDescribedTextWorkbench.Change.Clear(id)))
    }
    fun keep(id:MetadataDescribedTextWorkbench.Identity):MetadataArtLyricsUiState =
        copy(operations=operations-id)
    fun reset():MetadataArtLyricsUiState = copy(
        operations=emptyMap(), artwork=null, artworkSource=null, artworkEdit=null,
    )
}

internal data class ArtworkSource(
    val bytes: ByteArray,
    val mime: String,
    val widthPx: Int,
    val heightPx: Int,
    val ordinal: Int?,
)

internal sealed interface ArtworkEdit {
    val widthPx: Int
    val heightPx: Int
    data class Add(
        val bytes: ByteArray,
        val mime: String,
        override val widthPx: Int,
        override val heightPx: Int,
    ) : ArtworkEdit
    data class Replace(
        val ordinal: Int,
        val sourceSha256: String,
        val bytes: ByteArray,
        val mime: String,
        override val widthPx: Int,
        override val heightPx: Int,
    ) : ArtworkEdit
    data class Remove(
        val ordinal: Int,
        val sourceSha256: String,
    ) : ArtworkEdit {
        override val widthPx: Int get() = 0
        override val heightPx: Int get() = 0
    }
}

internal enum class ArtworkFit { CROP, CONTAIN }
internal enum class ArtworkAnchor { CENTER, TOP, BOTTOM, START, END }

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
    var addingArtwork by remember(sourceUri) { mutableStateOf(false) }
    var visibleTextEntries by remember(sourceUri) { mutableStateOf(8) }
    var showTypedEntries by remember(sourceUri) { mutableStateOf(false) }
    var widthText by remember(sourceUri) { mutableStateOf("") }
    var heightText by remember(sourceUri) { mutableStateOf("") }
    var preserveAspect by remember(sourceUri) { mutableStateOf(true) }
    var fit by remember(sourceUri) { mutableStateOf(ArtworkFit.CROP) }
    var anchor by remember(sourceUri) { mutableStateOf(ArtworkAnchor.CENTER) }
    var showEditor by remember(sourceUri) { mutableStateOf(false) }
    var showAnchorMenu by remember(sourceUri) { mutableStateOf(false) }
    var confirmRemove by remember(sourceUri) { mutableStateOf(false) }
    var showAddText by remember(sourceUri) { mutableStateOf(false) }
    var addKind by remember(sourceUri) {
        mutableStateOf(MetadataId3DescribedTagCodec.Kind.COMMENT)
    }
    var addLanguage by remember(sourceUri) { mutableStateOf("eng") }
    var addDescription by remember(sourceUri) { mutableStateOf("") }
    var addContent by remember(sourceUri) { mutableStateOf("") }
    var transformJob by remember(sourceUri) { mutableStateOf<Job?>(null) }
    val latestState by rememberUpdatedState(state)

    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target=pickedOrdinal
        val add=addingArtwork
        pickedOrdinal=null
        addingArtwork=false
        if(uri!=null) scope.launch {
            val bytes=MetadataArtLyricsOnDemandReader.readPickedImage(context.contentResolver,uri)
            if(bytes==null) {message="La imagen es ilegible o excede 8 MiB";return@launch}
            val isJpeg=bytes.size>=4 && bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte()
            val isPng=bytes.size>=8 && bytes[0]==0x89.toByte() && bytes[1]==0x50.toByte()
            if(!isJpeg && !isPng) {message="Formato de imagen no admitido";return@launch}
            val mime=if(isJpeg) "image/jpeg" else "image/png"
            val dims=withContext(Dispatchers.Default) { orientedDimensions(bytes) }
            if(dims==null) {message="La imagen no se pudo decodificar de forma segura";return@launch}
            val ordinal=if(add) null else target
            onChange(latestState.copy(
                artworkSource=ArtworkSource(bytes,mime,dims.first,dims.second,ordinal),
                artwork=null,
                artworkEdit=null,
            ))
            widthText=dims.first.toString()
            heightText=dims.second.toString()
            showEditor=true
            message=null
        }
    }

    fun launchPicker(ordinal:Int?, add:Boolean) {
        pickedOrdinal=ordinal
        addingArtwork=add
        picker.launch("image/*")
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
                val source=state.artworkSource
                val originalBytes=front?.image
                val originalMime=front?.info?.mime ?: "image/jpeg"
                val originalDims=front?.let{orientedDimensions(it.image)} ?: (0 to 0)
                val bytes=source?.bytes ?: originalBytes
                val mime=source?.mime ?: originalMime
                val dims=source?.let{it.widthPx to it.heightPx} ?: originalDims
                val ordinal=source?.ordinal ?: front?.ordinal
                val editableSource=bytes!=null && dims.first>0 && dims.second>0

                androidx.compose.runtime.LaunchedEffect(source?.bytes?.contentHashCode(), originalBytes?.contentHashCode()) {
                    if(editableSource && widthText.isBlank() && heightText.isBlank()) {
                        widthText=dims.first.toString()
                        heightText=dims.second.toString()
                    }
                }

                if(bytes!=null) {
                    MetadataEmbeddedPicture(bytes,source?.bytes?.contentHashCode()?.toString() ?: front?.info?.sha256 ?: "",
                        height=200.dp)
                    Text(
                        "${dims.first} × ${dims.second} px · $mime · ${bytes.size/1024} KiB",
                        style=MaterialTheme.typography.bodySmall,
                        color=TextSecondary,
                        modifier=Modifier.testTag("metadata_artwork_info"),
                    )
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        if(front!=null) {
                            TextButton(onClick={launchPicker(front.ordinal,false)},
                                modifier=Modifier.sizeIn(minHeight=48.dp)
                                    .testTag("metadata_change_artwork")) { Text("Cambiar") }
                            TextButton(onClick={confirmRemove=true},
                                modifier=Modifier.sizeIn(minHeight=48.dp)
                                    .testTag("metadata_remove_artwork")) { Text("Quitar") }
                        }
                        TextButton(onClick={showEditor=!showEditor},
                            modifier=Modifier.sizeIn(minHeight=48.dp)
                                .testTag("metadata_artwork_resize")) {
                            Text(if(showEditor) "Ocultar tamaño" else "Redimensionar")
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(160.dp).background(AeroGraphite,MichiShapes.md)
                        .border(1.dp,AeroHairline,MichiShapes.md),
                        contentAlignment=Alignment.Center) {
                        Text("Sin carátula incrustada verificada",color=TextSecondary)
                    }
                    if(snapshot.artworks.isEmpty()) {
                        TextButton(
                            onClick={launchPicker(null,true)},
                            modifier=Modifier.sizeIn(minHeight=48.dp)
                                .testTag("metadata_add_artwork"),
                        ) { Text("Añadir carátula") }
                    }
                }

                if(showEditor && editableSource) {
                    Text("Tamaño de la carátula",style=MaterialTheme.typography.titleSmall,color=PureWhite)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        ArtworkNumberField(
                            label="Ancho (px)", value=widthText, tag="metadata_artwork_width",
                            onValueChange={ changed ->
                                widthText=changed
                                if(preserveAspect) {
                                    val w=changed.toIntOrNull()
                                    if(w!=null && w>0 && dims.second>0) {
                                        heightText=((w.toLong()*dims.second)/dims.first).toString()
                                    }
                                }
                            },
                        )
                        ArtworkNumberField(
                            label="Alto (px)", value=heightText, tag="metadata_artwork_height",
                            onValueChange={ changed ->
                                heightText=changed
                                if(preserveAspect) {
                                    val h=changed.toIntOrNull()
                                    if(h!=null && h>0 && dims.first>0) {
                                        widthText=((h.toLong()*dims.first)/dims.second).toString()
                                    }
                                }
                            },
                        )
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Switch(
                            checked=preserveAspect,
                            onCheckedChange={preserveAspect=it},
                            modifier=Modifier.testTag("metadata_artwork_preserve"),
                        )
                        Text("Mantener proporción",color=TextSecondary)
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        listOf(500,600,1000).forEach { side ->
                            TextButton(onClick={
                                widthText=side.toString()
                                heightText=side.toString()
                            }, modifier=Modifier.sizeIn(minHeight=48.dp)
                                .testTag("metadata_artwork_preset_$side")) {
                                Text("$side×$side")
                            }
                        }
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={fit=ArtworkFit.CROP},
                            modifier=Modifier.testTag("metadata_artwork_crop")) {
                            Text("Recortar",color=if(fit==ArtworkFit.CROP) PureWhite else TextSecondary)
                        }
                        TextButton(onClick={fit=ArtworkFit.CONTAIN},
                            modifier=Modifier.testTag("metadata_artwork_contain")) {
                            Text("Ajustar",color=if(fit==ArtworkFit.CONTAIN) PureWhite else TextSecondary)
                        }
                        if(fit==ArtworkFit.CROP) {
                            Box {
                                TextButton(onClick={showAnchorMenu=true},
                                    modifier=Modifier.testTag("metadata_artwork_anchor")) {
                                    Text("Encuadre: ${anchor.label()}",color=TextSecondary)
                                }
                                DropdownMenu(expanded=showAnchorMenu,onDismissRequest={showAnchorMenu=false}) {
                                    ArtworkAnchor.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text={Text(option.label())},
                                            onClick={showAnchorMenu=false;anchor=option},
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Button(
                        onClick={
                            val w=widthText.toIntOrNull()
                            val h=heightText.toIntOrNull()
                            if(w==null || h==null || w !in 1..4096 || h !in 1..4096 ||
                                w.toLong()*h.toLong() > 8_000_000L) {
                                message="Indica un ancho y alto válidos (máximo 4096 px y 8 MP)"
                            } else {
                                transformJob?.cancel()
                                transformJob=scope.launch {
                                    val result=withContext(Dispatchers.Default) {
                                        runCatching {
                                            transformArtwork(bytes,mime,w,h,fit,anchor)
                                        }
                                    }
                                    result.onSuccess { transformed ->
                                        val proposedMime=if(mime=="image/png") "image/png" else "image/jpeg"
                                        if(ordinal!=null) {
                                            val reviewed=MetadataArtworkReplacementPreview.review(
                                                snapshot,ordinal,transformed,proposedMime)
                                            when(reviewed) {
                                                is MetadataArtworkReplacementPreview.Result.Ready -> {
                                                    onChange(latestState.copy(
                                                        artwork=reviewed.proposal,
                                                        artworkEdit=ArtworkEdit.Replace(
                                                            ordinal,reviewed.proposal.sourceSha256,
                                                            transformed,proposedMime,w,h),
                                                    ))
                                                    message=null
                                                }
                                                is MetadataArtworkReplacementPreview.Result.Blocked ->
                                                    message="No se pudo aplicar: ${reviewed.reason.name.lowercase()}"
                                            }
                                        } else if(snapshot.artworks.isEmpty()) {
                                            onChange(latestState.copy(
                                                artwork=null,
                                                artworkEdit=ArtworkEdit.Add(transformed,proposedMime,w,h),
                                            ))
                                            message=null
                                        } else message="Ya existe una carátula; usa Cambiar"
                                    }.onFailure {
                                        message="No se pudo transformar la imagen"
                                    }
                                }
                            }
                        },
                        modifier=Modifier.sizeIn(minHeight=48.dp)
                            .testTag("metadata_artwork_apply"),
                    ) { Text("Aplicar tamaño") }
                }

                val edit=state.artworkEdit
                if(edit!=null) {
                    when(edit) {
                        is ArtworkEdit.Add -> {
                            Text("Carátula nueva · ${edit.widthPx} × ${edit.heightPx} px",
                                color=TertiaryCyan,style=MaterialTheme.typography.labelMedium)
                            MetadataEmbeddedPicture(edit.bytes,edit.widthPx.toString(),height=140.dp)
                        }
                        is ArtworkEdit.Replace -> {
                            Text("Carátula propuesta · ${edit.widthPx} × ${edit.heightPx} px",
                                color=TertiaryCyan,style=MaterialTheme.typography.labelMedium)
                            MetadataEmbeddedPicture(edit.bytes,edit.bytes.contentHashCode().toString(),height=140.dp)
                        }
                        is ArtworkEdit.Remove -> Text(
                            "La carátula se quitará al guardar.",
                            color=MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(
                        onClick={onChange(state.copy(artwork=null,artworkEdit=null,artworkSource=null))},
                        modifier=Modifier.sizeIn(minHeight=48.dp),
                    ) { Text("Descartar propuesta de carátula") }
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
                if(snapshot.format!=MetadataArtworkLyricsProbe.Format.FLAC) {
                    val pendingAdds=state.operations.values
                        .filterIsInstance<MetadataDescribedTextWorkbench.Change.Add>()
                        .filter { change -> snapshot.texts.none { it.identity==change.identity } }
                    TextButton(
                        onClick={showAddText=true},
                        modifier=Modifier.sizeIn(minHeight=48.dp)
                            .testTag("metadata_add_described_text"),
                    ) { Text("Añadir comentario o letra") }
                    pendingAdds.forEach { change ->
                        MetadataPendingTextCard(
                            change,
                            onText={onChange(state.updateDraft(change.identity,it))},
                            onKeep={onChange(state.keep(change.identity))},
                        )
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
                if(confirmRemove && front!=null) {
                    AlertDialog(
                        onDismissRequest={confirmRemove=false},
                        title={Text("¿Quitar la carátula?")},
                        text={Text("Se quitará del archivo solo cuando Guardar escriba y verifique el original.")},
                        confirmButton={
                            TextButton(
                                onClick={
                                    confirmRemove=false
                                    onChange(state.copy(
                                        artwork=null,
                                        artworkSource=null,
                                        artworkEdit=ArtworkEdit.Remove(front.ordinal,front.info.sha256),
                                    ))
                                },
                                modifier=Modifier.testTag("metadata_confirm_remove_artwork"),
                            ) { Text("Quitar",color=MaterialTheme.colorScheme.error) }
                        },
                        dismissButton={
                            TextButton(onClick={confirmRemove=false}) { Text("Cancelar") }
                        },
                    )
                }
                if(showAddText) {
                    val languageValid=addLanguage.matches(Regex("[a-z]{3}"))
                    val identity=MetadataDescribedTextWorkbench.Identity(
                        addKind,addLanguage,addDescription)
                    val duplicated=snapshot.texts.any { it.identity==identity }
                    val descriptionValid=addDescription.length<=128 &&
                        addDescription.none { it.isISOControl() } && '\ufffd' !in addDescription
                    val contentValid=addContent.isNotBlank() &&
                        addContent.length<=addKind.maxChars
                    val valid=languageValid && descriptionValid && contentValid && !duplicated
                    Dialog(onDismissRequest={showAddText=false}) {
                        AeroFloatingSurface(
                            shape=RoundedCornerShape(20.dp),
                            modifier=Modifier.fillMaxWidth().padding(8.dp)
                                .testTag("metadata_add_described_dialog"),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().aeroModalFormViewport()
                                    .padding(20.dp),
                                verticalArrangement=Arrangement.spacedBy(MichiSpacing.sm),
                            ) {
                                Text("Añadir comentario o letra",
                                    style=MaterialTheme.typography.titleLarge,color=PureWhite)
                                Row(horizontalArrangement=Arrangement.spacedBy(MichiSpacing.sm)) {
                                    TextButton(
                                        onClick={addKind=MetadataId3DescribedTagCodec.Kind.COMMENT},
                                        modifier=Modifier.sizeIn(minHeight=48.dp)
                                            .testTag("metadata_add_kind_comment"),
                                    ) {
                                        Text(if(addKind==MetadataId3DescribedTagCodec.Kind.COMMENT)
                                            "✓ Comentario" else "Comentario")
                                    }
                                    TextButton(
                                        onClick={addKind=MetadataId3DescribedTagCodec.Kind.LYRICS},
                                        modifier=Modifier.sizeIn(minHeight=48.dp)
                                            .testTag("metadata_add_kind_lyrics"),
                                    ) {
                                        Text(if(addKind==MetadataId3DescribedTagCodec.Kind.LYRICS)
                                            "✓ Letra" else "Letra")
                                    }
                                }
                                OutlinedTextField(
                                    value=addLanguage,
                                    onValueChange={ next ->
                                        addLanguage=next.filter { it in 'a'..'z' }.take(3)
                                    },
                                    label={Text("Idioma (3 letras)")},singleLine=true,
                                    modifier=Modifier.fillMaxWidth()
                                        .testTag("metadata_add_described_language"),
                                )
                                OutlinedTextField(
                                    value=addDescription,
                                    onValueChange={ next -> addDescription=next.take(128) },
                                    label={Text("Descriptor (opcional)")},singleLine=true,
                                    modifier=Modifier.fillMaxWidth()
                                        .testTag("metadata_add_described_description"),
                                )
                                OutlinedTextField(
                                    value=addContent,
                                    onValueChange={ addContent=it.take(addKind.maxChars) },
                                    label={Text("Contenido")},
                                    minLines=if(addKind==MetadataId3DescribedTagCodec.Kind.LYRICS) 4 else 1,
                                    maxLines=8,isError=addContent.isBlank(),
                                    modifier=Modifier.fillMaxWidth()
                                        .testTag("metadata_add_described_content"),
                                )
                                if(duplicated) Text(
                                    "Ya existe una entrada con ese idioma y descriptor.",
                                    color=MaterialTheme.colorScheme.error,
                                    style=MaterialTheme.typography.bodySmall,
                                )
                                Row(Modifier.fillMaxWidth(),
                                    horizontalArrangement=Arrangement.End) {
                                    TextButton(onClick={showAddText=false}) { Text("Cancelar") }
                                    Button(
                                        enabled=valid,
                                        onClick={
                                            onChange(state.add(identity,addContent))
                                            showAddText=false
                                            addContent=""
                                        },
                                        modifier=Modifier.testTag("metadata_add_described_confirm"),
                                    ) { Text("Añadir propuesta") }
                                }
                            }
                        }
                    }
                }
            }
        }
        message?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }
}

@Composable
private fun ArtworkNumberField(label:String,value:String,tag:String,onValueChange:(String)->Unit) {
    Column(Modifier.width(112.dp)) {
        Text(label,style=MaterialTheme.typography.labelSmall,color=TextSecondary)
        BasicTextField(
            value=value,
            onValueChange={ next -> if(next.isEmpty() || next.all(Char::isDigit)) onValueChange(next) },
            singleLine=true,
            textStyle=MaterialTheme.typography.bodyLarge.copy(color=PureWhite),
            cursorBrush=SolidColor(TertiaryCyan),
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
            modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)
                .background(AeroGraphite,MichiShapes.md)
                .border(1.dp,AeroHairline,MichiShapes.md)
                .padding(horizontal=12.dp)
                .testTag(tag),
        )
    }
}

private fun ArtworkAnchor.label():String=when(this){
    ArtworkAnchor.CENTER->"Centro"
    ArtworkAnchor.TOP->"Arriba"
    ArtworkAnchor.BOTTOM->"Abajo"
    ArtworkAnchor.START->"Izquierda"
    ArtworkAnchor.END->"Derecha"
}

private fun orientedDimensions(bytes:ByteArray):Pair<Int,Int>? {
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    if(bounds.outWidth !in 1..12000 || bounds.outHeight !in 1..12000) return null
    val orientation=try {
        ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
    } catch(_:Exception){ExifInterface.ORIENTATION_NORMAL}
    val swap=orientation in setOf(
        ExifInterface.ORIENTATION_ROTATE_90,ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE,ExifInterface.ORIENTATION_TRANSVERSE)
    return if(swap) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
}

internal fun transformArtwork(
    bytes:ByteArray,mime:String,targetW:Int,targetH:Int,
    fit:ArtworkFit,anchor:ArtworkAnchor,
):ByteArray {
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    require(bounds.outWidth>0 && bounds.outHeight>0)
    var sample=1
    while(maxOf(bounds.outWidth,bounds.outHeight)/sample > maxOf(targetW,targetH)) sample*=2
    val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size,
        BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})
        ?: error("DECODE_FAILED")
    val source=orient(decoded,bytes)
    val output=Bitmap.createBitmap(targetW,targetH,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(output)
    if(fit==ArtworkFit.CONTAIN) {
        if(mime!="image/png") canvas.drawColor(AndroidColor.WHITE)
        val scale=minOf(targetW.toFloat()/source.width,targetH.toFloat()/source.height)
        val drawW=source.width*scale
        val drawH=source.height*scale
        val left=(targetW-drawW)/2f
        val top=(targetH-drawH)/2f
        canvas.drawBitmap(source,null,RectF(left,top,left+drawW,top+drawH),Paint(Paint.FILTER_BITMAP_FLAG))
    } else {
        val targetRatio=targetW.toFloat()/targetH
        val sourceRatio=source.width.toFloat()/source.height
        val cropW:Int
        val cropH:Int
        if(sourceRatio>targetRatio) { cropH=source.height; cropW=(cropH*targetRatio).toInt() }
        else { cropW=source.width; cropH=(cropW/targetRatio).toInt() }
        val left=when(anchor){
            ArtworkAnchor.START->0
            ArtworkAnchor.END->source.width-cropW
            else->(source.width-cropW)/2
        }
        val top=when(anchor){
            ArtworkAnchor.TOP->0
            ArtworkAnchor.BOTTOM->source.height-cropH
            else->(source.height-cropH)/2
        }
        canvas.drawBitmap(source,
            android.graphics.Rect(left,top,left+cropW,top+cropH),
            RectF(0f,0f,targetW.toFloat(),targetH.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG))
    }
    val out=java.io.ByteArrayOutputStream()
    val format=if(mime=="image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    check(output.compress(format,if(format==Bitmap.CompressFormat.JPEG) 95 else 100,out))
    source.recycle()
    if(source!==decoded && !decoded.isRecycled) decoded.recycle()
    output.recycle()
    val result=out.toByteArray()
    require(result.size in 16..MetadataArtworkReplacementPreview.MAX_IMAGE_BYTES) { "IMAGE_TOO_LARGE" }
    return result
}

private fun orient(bitmap:Bitmap,bytes:ByteArray):Bitmap {
    val orientation=try {
        ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
    } catch(_:Exception){ExifInterface.ORIENTATION_NORMAL}
    val matrix=Matrix()
    when(orientation){
        ExifInterface.ORIENTATION_ROTATE_90->matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180->matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270->matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL->matrix.postScale(-1f,1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL->matrix.postScale(1f,-1f)
        ExifInterface.ORIENTATION_TRANSPOSE->{matrix.postRotate(90f);matrix.postScale(-1f,1f)}
        ExifInterface.ORIENTATION_TRANSVERSE->{matrix.postRotate(270f);matrix.postScale(-1f,1f)}
        else->return bitmap
    }
    val rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
    if(rotated!==bitmap) bitmap.recycle()
    return rotated
}

@Composable
private fun MetadataPendingTextCard(
    change:MetadataDescribedTextWorkbench.Change.Add,
    onText:(String)->Unit,onKeep:()->Unit,
) {
    val kind=change.identity.kind
    Column(Modifier.fillMaxWidth().background(AeroGraphite,MichiShapes.md)
        .border(1.dp,TertiaryCyan,MichiShapes.md).padding(MichiSpacing.md),
        verticalArrangement=Arrangement.spacedBy(MichiSpacing.sm)) {
        Text("Nueva entrada · ${if(kind==MetadataId3DescribedTagCodec.Kind.COMMENT) "Comentario" else "Letra"} · ${change.identity.language}",
            style=MaterialTheme.typography.titleSmall,color=TertiaryCyan)
        Text("Descriptor: ${change.identity.description.ifBlank { "(sin descriptor)" }}",
            color=TextSecondary,style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(value=change.text,onValueChange=onText,
            label={Text("Contenido de la nueva entrada")},modifier=Modifier.fillMaxWidth()
                .testTag("metadata_described_new_${kind.name}_${change.identity.language}_${change.identity.description}"),
            minLines=if(kind==MetadataId3DescribedTagCodec.Kind.LYRICS) 4 else 1,
            maxLines=8,isError=change.text.isBlank())
        if(change.text.isBlank()) Text(
            "El contenido no puede quedar vacío; quita la entrada si no la necesitas.",
            color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        TextButton(onClick=onKeep,modifier=Modifier.sizeIn(minHeight=48.dp))
            {Text("Quitar entrada nueva")}
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
