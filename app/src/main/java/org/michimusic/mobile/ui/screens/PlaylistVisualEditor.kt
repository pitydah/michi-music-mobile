package org.michimusic.mobile.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import org.michimusic.mobile.ui.components.premium.AeroFloatingSurface
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * Explicit two-field appearance editor. Image selections are staged in memory
 * and only imported/persisted when the user confirms. Cancel leaves the store
 * untouched; a failed import keeps the previous image and reports the reason.
 */
@Composable
internal fun PlaylistVisualEditorDialog(
    visual: PlaylistVisualState,
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (PlaylistVisualEditRequest) -> Unit,
) {
    var coverPick by remember(visual) { mutableStateOf<Uri?>(null) }
    var coverRemove by remember(visual) { mutableStateOf(false) }
    var bannerPick by remember(visual) { mutableStateOf<Uri?>(null) }
    var bannerRemove by remember(visual) { mutableStateOf(false) }
    var coverFocalX by remember(visual) {
        mutableFloatStateOf(safePlaylistBannerFocal(visual.coverFocalX))
    }
    var coverFocalY by remember(visual) {
        mutableFloatStateOf(safePlaylistBannerFocal(visual.coverFocalY))
    }
    var coverScale by remember(visual) { mutableFloatStateOf(safePlaylistScale(visual.coverScale)) }
    var bannerFocalX by remember(visual) {
        mutableFloatStateOf(safePlaylistBannerFocal(visual.bannerFocalX))
    }
    var bannerFocalY by remember(visual) {
        mutableFloatStateOf(safePlaylistBannerFocal(visual.bannerFocalY))
    }
    var bannerScale by remember(visual) { mutableFloatStateOf(safePlaylistScale(visual.bannerScale)) }
    var adjustingCover by remember { mutableStateOf(false) }
    var adjustingBanner by remember { mutableStateOf(false) }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coverPick = uri
            coverRemove = false
            adjustingCover = true
        }
    }
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            bannerPick = uri
            bannerRemove = false
            adjustingBanner = true
        }
    }
    val coverModel: Any? = when {
        coverPick != null -> coverPick
        coverRemove -> null
        else -> visual.coverUri
    }
    val bannerModel: Any? = when {
        bannerPick != null -> bannerPick
        bannerRemove -> null
        else -> visual.bannerUri
    }
    val coverHasImage = coverPick != null || (!coverRemove && visual.coverUri != null)
    val bannerHasImage = bannerPick != null || (!bannerRemove && visual.bannerUri != null)

    fun request(): PlaylistVisualEditRequest = PlaylistVisualEditRequest(
        cover = when {
            coverPick != null -> PlaylistVisualImageChange.Replace(coverPick!!)
            coverRemove -> PlaylistVisualImageChange.Remove
            else -> PlaylistVisualImageChange.Unchanged
        },
        banner = when {
            bannerPick != null -> PlaylistVisualImageChange.Replace(bannerPick!!)
            bannerRemove -> PlaylistVisualImageChange.Remove
            else -> PlaylistVisualImageChange.Unchanged
        },
        coverFocalX = coverFocalX,
        coverFocalY = coverFocalY,
        coverScale = coverScale,
        bannerFocalX = bannerFocalX,
        bannerFocalY = bannerFocalY,
        bannerScale = bannerScale,
    )

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AeroFloatingSurface(
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .testTag("playlist_visual_editor"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Editar apariencia de playlist",
                            color = PureWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Portada y banner se guardan de forma independiente",
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isSaving,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = PrimaryPinkContainer,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .testTag("playlist_visual_editor_error"),
                    )
                }

                Spacer(Modifier.height(16.dp))

                PlaylistVisualFieldEditor(
                    label = "Portada",
                    tagPrefix = "playlist_visual_editor_cover",
                    imageModel = coverModel,
                    hasImage = coverHasImage,
                    isRemoving = coverRemove,
                    adjusting = adjustingCover,
                    focalX = coverFocalX,
                    focalY = coverFocalY,
                    scale = coverScale,
                    onAdjustingChange = { adjustingCover = it },
                    onPick = { coverPicker.launch(arrayOf("image/*")) },
                    onRemove = {
                        coverPick = null
                        coverRemove = true
                        adjustingCover = false
                    },
                    onUndoRemove = { coverRemove = false },
                    onFocalDelta = { dx, dy ->
                        coverFocalX = (coverFocalX + dx).coerceIn(0f, 1f)
                        coverFocalY = (coverFocalY + dy).coerceIn(0f, 1f)
                    },
                    onFocalXChange = { coverFocalX = it.coerceIn(0f, 1f) },
                    onFocalYChange = { coverFocalY = it.coerceIn(0f, 1f) },
                    onScaleChange = { coverScale = safePlaylistScale(it) },
                    onReset = {
                        coverFocalX = 0.5f
                        coverFocalY = 0.5f
                        coverScale = 1f
                    },
                )

                Spacer(Modifier.height(18.dp))

                PlaylistVisualFieldEditor(
                    label = "Banner",
                    tagPrefix = "playlist_visual_editor_banner",
                    imageModel = bannerModel,
                    hasImage = bannerHasImage,
                    isRemoving = bannerRemove,
                    adjusting = adjustingBanner,
                    focalX = bannerFocalX,
                    focalY = bannerFocalY,
                    scale = bannerScale,
                    onAdjustingChange = { adjustingBanner = it },
                    onPick = { bannerPicker.launch(arrayOf("image/*")) },
                    onRemove = {
                        bannerPick = null
                        bannerRemove = true
                        adjustingBanner = false
                    },
                    onUndoRemove = { bannerRemove = false },
                    onFocalDelta = { dx, dy ->
                        bannerFocalX = (bannerFocalX + dx).coerceIn(0f, 1f)
                        bannerFocalY = (bannerFocalY + dy).coerceIn(0f, 1f)
                    },
                    onFocalXChange = { bannerFocalX = it.coerceIn(0f, 1f) },
                    onFocalYChange = { bannerFocalY = it.coerceIn(0f, 1f) },
                    onScaleChange = { bannerScale = safePlaylistScale(it) },
                    onReset = {
                        bannerFocalX = 0.5f
                        bannerFocalY = 0.5f
                        bannerScale = 1f
                    },
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isSaving,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("playlist_visual_editor_cancel"),
                    ) {
                        Text("Cancelar")
                    }
                    Button(
                        onClick = { if (!isSaving) onConfirm(request()) },
                        enabled = !isSaving,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryPinkContainer,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("playlist_visual_editor_confirm"),
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                color = PureWhite,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Text("Guardar apariencia", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistVisualFieldEditor(
    label: String,
    tagPrefix: String,
    imageModel: Any?,
    hasImage: Boolean,
    isRemoving: Boolean,
    adjusting: Boolean,
    focalX: Float,
    focalY: Float,
    scale: Float,
    onAdjustingChange: (Boolean) -> Unit,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    onUndoRemove: () -> Unit,
    onFocalDelta: (Float, Float) -> Unit,
    onFocalXChange: (Float) -> Unit,
    onFocalYChange: (Float) -> Unit,
    onScaleChange: (Float) -> Unit,
    onReset: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = PureWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (hasImage || isRemoving) {
                Text(
                    text = when {
                        isRemoving -> "Se quitará al guardar"
                        else -> "Personalizada"
                    },
                    color = if (isRemoving) PrimaryPinkContainer else TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        PlaylistVisualPreview(
            imageModel = imageModel,
            adjusting = adjusting,
            focalX = focalX,
            focalY = focalY,
            scale = scale,
            isRemoving = isRemoving,
            label = label,
            onFocalDelta = onFocalDelta,
            testTag = "${tagPrefix}_preview",
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onPick,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("${tagPrefix}_pick"),
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(if (hasImage) "Reemplazar" else "Elegir")
            }
            TextButton(
                onClick = { onAdjustingChange(!adjusting) },
                enabled = hasImage,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("${tagPrefix}_adjust"),
            ) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(if (adjusting) "Cerrar ajuste" else "Ajustar")
            }
            if (isRemoving) {
                TextButton(
                    onClick = onUndoRemove,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("${tagPrefix}_undo_remove"),
                ) { Text("Deshacer") }
            } else {
                TextButton(
                    onClick = onRemove,
                    enabled = hasImage,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("${tagPrefix}_remove"),
                ) { Text("Quitar") }
            }
        }
        if (adjusting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Zoom",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(scale * 100).roundToInt()}%",
                    color = PureWhite,
                    fontSize = 12.sp,
                )
            }
            Slider(
                value = scale,
                onValueChange = { onScaleChange(it) },
                valueRange = 1f..3f,
                colors = SliderDefaults.colors(
                    thumbColor = PrimaryPinkContainer,
                    activeTrackColor = PrimaryPinkContainer,
                    inactiveTrackColor = PureWhite.copy(alpha = 0.22f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("${tagPrefix}_scale"),
            )
            Text(
                text = "Encuadre horizontal",
                color = TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            Slider(
                value = focalX,
                onValueChange = { onFocalXChange(it) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = PrimaryPinkContainer,
                    activeTrackColor = PrimaryPinkContainer,
                    inactiveTrackColor = PureWhite.copy(alpha = 0.22f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("${tagPrefix}_focal_x"),
            )
            Text(
                text = "Encuadre vertical",
                color = TextSecondary,
                fontSize = 11.sp,
            )
            Slider(
                value = focalY,
                onValueChange = { onFocalYChange(it) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = PrimaryPinkContainer,
                    activeTrackColor = PrimaryPinkContainer,
                    inactiveTrackColor = PureWhite.copy(alpha = 0.22f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("${tagPrefix}_focal_y"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Arrastra sobre la vista previa para mover el encuadre.",
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onReset,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("${tagPrefix}_reset"),
                ) { Text("Restablecer") }
            }
        }
    }
}

@Composable
private fun PlaylistVisualPreview(
    imageModel: Any?,
    adjusting: Boolean,
    focalX: Float,
    focalY: Float,
    scale: Float,
    isRemoving: Boolean,
    label: String,
    onFocalDelta: (Float, Float) -> Unit,
    testTag: String,
) {
    var failed by remember(imageModel) { mutableStateOf(false) }
    val safeScale = safePlaylistScale(scale)
    val alignment = playlistFocalAlignment(focalX, focalY)
    val origin = TransformOrigin(
        safePlaylistBannerFocal(focalX),
        safePlaylistBannerFocal(focalY),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(146.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(AeroGraphite, SurfaceObsidian)))
            .border(0.65.dp, PureWhite.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
            .then(
                if (adjusting && imageModel != null) {
                    Modifier.pointerInput(imageModel, safeScale) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (size.width > 0 && size.height > 0) {
                                onFocalDelta(
                                    -dragAmount.x / size.width / safeScale,
                                    -dragAmount.y / size.height / safeScale,
                                )
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .testTag(testTag),
    ) {
        if (imageModel != null && !failed) {
            AsyncImage(
                model = imageModel,
                contentDescription = "Vista previa de $label",
                contentScale = ContentScale.Crop,
                alignment = alignment,
                onError = { failed = true },
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer(
                        scaleX = safeScale,
                        scaleY = safeScale,
                        transformOrigin = origin,
                    ),
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        isRemoving -> "Se quitará al guardar"
                        else -> "Sin $label"
                    },
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            }
        }
        if (adjusting && imageModel != null) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawCircle(
                    color = PrimaryPinkContainer.copy(alpha = 0.95f),
                    radius = 7.dp.toPx(),
                    center = Offset(
                        safePlaylistBannerFocal(focalX) * size.width,
                        safePlaylistBannerFocal(focalY) * size.height,
                    ),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
            Text(
                text = "Arrastra para encuadrar",
                color = PureWhite,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}
