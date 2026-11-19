package org.michimusic.mobile.devices.aircat.experience

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.devices.aircat.AirCatTransferState
import org.michimusic.mobile.ui.components.AeroSurface
import org.michimusic.mobile.ui.components.AeroPremiumBackdrop
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * A71: the AirCat experience surface.
 *
 * Pure rendering of [AirCatExperienceState]. It starts no request, opens no
 * socket, reads no Keystore entry and writes nothing: every action leaves as an
 * [AirCatIntent] and the controller decides what may happen. That split is what
 * keeps a Compose preview (and a test) from ever becoming an authority.
 */

private val screenPadding = MichiSpacing.screenHorizontal
private val blockGap = MichiSpacing.lg

@Composable
internal fun AirCatExperienceContent(
    state: AirCatExperienceState,
    onIntent: (AirCatIntent) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    onSearchDevices: () -> Unit = {},
    onManageHome: () -> Unit = {},
    recoveries: List<AirCatRecoveryUi> = emptyList(),
    onRepairSource: (String) -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize().testTag("aircat_experience")) {
        AeroPremiumBackdrop(accent = TertiaryCyan)
        Column(modifier = Modifier.fillMaxSize()) {
            val title = when (state.phase) {
                AirCatPhase.HUB -> "AirCat"
                AirCatPhase.SOURCES -> "Elegir equipo de origen"
                AirCatPhase.CATALOGUE -> "Explorar música"
                AirCatPhase.REVIEW -> "Revisar recepción"
                AirCatPhase.TRANSFER -> "Recibiendo música"
                AirCatPhase.RESULT -> "Resultado de AirCat"
            }
            AirCatAeroHeader(
                title = title,
                eyebrow = "Michi Devices · AirCat",
                subtitle = "Archivos originales · biblioteca musical",
                onBack = {
                    when (state.phase) {
                        AirCatPhase.HUB -> onClose()
                        AirCatPhase.TRANSFER -> onIntent(AirCatIntent.Back)
                        AirCatPhase.RESULT -> onIntent(AirCatIntent.DismissResult)
                        else -> onIntent(AirCatIntent.Back)
                    }
                },
            )
            Box(Modifier.weight(1f)) {
                when (state.phase) {
                    AirCatPhase.HUB -> AirCatHubContent(state, onIntent, onSearchDevices, onManageHome, recoveries, onRepairSource)
                    AirCatPhase.SOURCES -> AirCatSourcePickerContent(state, onIntent, onSearchDevices, onManageHome)
                    AirCatPhase.CATALOGUE -> AirCatCatalogueContent(state, onIntent)
                    AirCatPhase.REVIEW -> AirCatReviewContent(state, onIntent)
                    AirCatPhase.TRANSFER -> AirCatTransferContent(state, onIntent)
                    AirCatPhase.RESULT -> AirCatResultContent(state, onIntent)
                }
            }
        }
    }
}

// --- Hub -------------------------------------------------------------------

@Composable
private fun AirCatRecoveryReviewDialog(
    record: AirCatRecoveryUi,
    sourceName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = SurfaceObsidian,
        title = {
            Text("Revisar reparación local", color = PureWhite, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MichiSpacing.xs)) {
                Text("Equipo: $sourceName", color = PureWhite)
                Text("Transferencia: ${record.transferId.take(12)}…", color = TextSecondary)
                Text("${record.pendingTracks} archivo(s) afectado(s)", color = TextSecondary)
                Text(
                    "Repara el estado local de este teléfono. No descarga archivos " +
                        "ni solicita permisos.",
                    color = TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Reparar estado local") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancelar") }
        },
    )
}

@Composable
private fun AirCatHubContent(
    state: AirCatExperienceState,
    onIntent: (AirCatIntent) -> Unit,
    onSearchDevices: () -> Unit,
    onManageHome: () -> Unit,
    recoveries: List<AirCatRecoveryUi>,
    onRepairSource: (String) -> Unit,
) {
    // A recovery is only a proposal until the person reviews it. Nothing is
    // repaired from the row itself; this local state gates the confirmation.
    var review by remember { mutableStateOf<AirCatRecoveryUi?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("aircat_hub_screen"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(screenPadding),
        verticalArrangement = Arrangement.spacedBy(blockGap),
    ) {
        item {
            AirCatAeroHero(
                title = "Tu música, entre equipos Michi",
                detail = "Recibe archivos originales desde tus equipos de confianza, " +
                    "sin conversión y con verificación de integridad.",
                actionLabel = "Recibir música",
                actionTag = "aircat_receive_button",
                onAction = { onIntent(AirCatIntent.OpenSources) },
                icon = Icons.Filled.AddLink,
            )
        }
        if (recoveries.isNotEmpty()) {
            item(key = "aircat_recovery_index") {
                AirCatAeroSectionTitle("Reparaciones locales disponibles",
                    "Checkpoints persistidos; no hay reanudación automática de red.")
            }
            items(recoveries, key = { "recover:${it.sourceDeviceId}:${it.transferId}" }) { record ->
                // Recovery rows carry the canonical Michi identity; the registry
                // id is only a legacy fallback for display, never for the key.
                val sourceName = state.sources.firstOrNull { it.canonicalMichiId == record.sourceDeviceId }?.name
                    ?: state.sources.firstOrNull { it.deviceId == record.sourceDeviceId }?.name
                    ?: "Equipo Michi conocido"
                AirCatAeroRecoveryCard(record,
                    sourceName = sourceName,
                    onReview = { review = record },
                )
                if (review == record) {
                    AirCatRecoveryReviewDialog(
                        record = record,
                        sourceName = sourceName,
                        onConfirm = {
                            onRepairSource(record.sourceDeviceId)
                            review = null
                        },
                        onCancel = { review = null },
                    )
                }
            }
        }

        state.transfer?.let { transfer ->
            item { ActiveTransferCard(transfer, onIntent) }
        }

        state.result?.let { result ->
            item { ResultCard(result, onIntent) }
        }

        item {
            AeroSurface(role = AeroSurfaceRole.Content, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(MichiSpacing.lg)) {
                    Text("Equipos disponibles", color = PureWhite, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(MichiSpacing.sm))
                    if (state.sources.isEmpty()) {
                        AirCatAeroSectionTitle("Aún no hay orígenes conectados", "Activa Michi Link para detectar equipos próximos.")
                        Spacer(Modifier.height(MichiSpacing.xs))
                        Text("Busca un servidor o escritorio Michi que anuncie AirCat y " +
                            "vincúlalo a Home antes de recibir música.",
                            color = TextSecondary, fontSize = 13.sp)
                        Spacer(Modifier.height(MichiSpacing.md))
                        Button(onClick = onSearchDevices,
                            modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)) {
                            Text("Buscar equipos", color = SurfaceObsidian, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = onManageHome,
                            modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)) {
                            Text("Administrar confianza Home", color = TertiaryCyan)
                        }
                    } else {
                        state.sources.take(4).forEach { source ->
                            Spacer(Modifier.height(MichiSpacing.xs))
                            AirCatAeroFact("${source.name} · ${source.statusLine}")
                        }
                        if (state.sources.size > 4) {
                            Spacer(Modifier.height(MichiSpacing.sm))
                            Text("${state.sources.size - 4} equipos más en tu red",
                                color = TextSecondary, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(MichiSpacing.md))
                        TextButton(onClick = { onIntent(AirCatIntent.OpenSources) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)) {
                            Text("Explorar equipos de origen", color = TertiaryCyan)
                        }
                    }
                }
            }
        }

        state.message?.let { message ->
            item { NoticeText(message) }
        }
    }
}

// --- Sources ---------------------------------------------------------------

@Composable
private fun AirCatSourcePickerContent(
    state: AirCatExperienceState,
    onIntent: (AirCatIntent) -> Unit,
    onSearchDevices: () -> Unit,
    onManageHome: () -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("aircat_source_picker"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(screenPadding),
        verticalArrangement = Arrangement.spacedBy(blockGap),
    ) {
        item {
            Column {
                Text("¿Desde qué equipo quieres recibir música?",
                    style = MichiTypography.screenEyebrowTechnical)
                Spacer(Modifier.height(MichiSpacing.xs))
                Text(
                    "Solo los equipos conectados, vinculados a Home y compatibles con AirCat pueden ofrecer su catálogo.",
                    color = TextSecondary, fontSize = 13.sp,
                )
            }
        }

        if (state.sourcesLoading) {
            item {
                Box(Modifier.fillMaxWidth().padding(MichiSpacing.xxl), Alignment.Center) {
                    CircularProgressIndicator(color = TertiaryCyan)
                }
            }
        }

        if (!state.sourcesLoading && state.sources.isEmpty()) {
            item {
                AirCatAeroEmptyState(
                    title = "Ningún equipo de origen disponible",
                    detail = "Busca equipos Michi en tu red local. Después revisa su vinculación Home.",
                    primaryLabel = "Buscar equipos", onPrimary = onSearchDevices,
                    secondaryLabel = "Confianza Home", onSecondary = onManageHome,
                )
            }
        }

        // The six facts stay separate: presence is not trust, and a capability
        // is not an authorisation. The row says which one is missing.
        items(state.sources, key = { it.deviceId }) { source ->
            AeroSurface(role = AeroSurfaceRole.Content, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(MichiSpacing.lg)) {
                    Text(source.name, color = PureWhite, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (source.kindLabel.isNotBlank()) {
                        Text(source.kindLabel, color = TextSecondary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(MichiSpacing.sm))
                    Text(source.statusLine, color = TextSecondary, fontSize = 13.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(MichiSpacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
                        if (source.canExplore) {
                            TextButton(
                                onClick = { onIntent(AirCatIntent.ChooseSource(source.deviceId)) },
                                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                                    .testTag("aircat_explore_${source.deviceId}"),
                            ) { Text("Explorar música") }
                        } else if (source.needsHome) {
                            TextButton(onClick = onManageHome,
                                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)) {
                                Text("Vincular confianza Home", color = TertiaryCyan)
                            }
                        } else if (!source.connected && !source.revoked) {
                            TextButton(onClick = onSearchDevices,
                                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)) {
                                Text("Buscar equipo", color = TertiaryCyan)
                            }
                        }
                    }
                }
            }
        }

        item {
            TextButton(
                onClick = { onIntent(AirCatIntent.Back) },
                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget),
            ) { Text("Volver") }
        }
    }
}

// --- Catalogue -------------------------------------------------------------

@Composable
private fun AirCatCatalogueContent(state: AirCatExperienceState, onIntent: (AirCatIntent) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("aircat_catalog_screen"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = screenPadding,
            end = screenPadding,
            top = MichiSpacing.md,
            bottom = MichiSpacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm),
    ) {
        // The header scrolls with the catalogue. Keeping it outside the list made
        // the 2.0x font scale consume the whole height and squeezed the footer's
        // Continue button to 0dp, leaving it invisible and untappable.
        item(key = "catalogue-header") {
            Column(verticalArrangement = Arrangement.spacedBy(MichiSpacing.xs)) {
                Text(
                    "Música de ${state.selectedSource?.name ?: "…"}",
                    color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
                Text("Selecciona canciones o álbumes. Solo se recibirá lo que confirmes.",
                    color = TextSecondary, fontSize = 13.sp)
                AirCatAeroFact("Archivos originales · sin conversión")
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { onIntent(AirCatIntent.QueryChanged(it)) },
                    singleLine = true,
                    label = { Text("Filtrar por título, artista o álbum") },
                    modifier = Modifier.fillMaxWidth().testTag("aircat_catalog_query"),
                )
                // Two text actions do not fit side by side at 2.0x on 360dp:
                // FlowRow wraps "Limpiar" instead of clipping it at the edge.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(MichiSpacing.xs),
                ) {
                    TextButton(
                        onClick = { onIntent(AirCatIntent.SelectAll) },
                        modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget),
                    ) { Text(if (state.query.isBlank()) "Seleccionar todo" else "Seleccionar visibles") }
                    TextButton(
                        onClick = { onIntent(AirCatIntent.ClearSelection) },
                        modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget),
                    ) { Text("Limpiar") }
                }
            }
        }
        if (state.catalogueLoading) {
            item(key = "catalogue-loading") {
                Box(Modifier.fillMaxWidth().height(240.dp), Alignment.Center) {
                    CircularProgressIndicator(color = TertiaryCyan)
                }
            }
        } else {
            state.visibleAlbums.forEach { album ->
                item(key = "album:${album.album}") {
                    AlbumHeader(
                        album = album,
                        selection = state.selection,
                        onIntent = onIntent,
                    )
                }
                items(album.tracks, key = { it.remoteTrackId }) { track ->
                    TrackRow(track, state.selection.contains(track.remoteTrackId), onIntent)
                }
            }
            if (state.visibleTracks.isEmpty()) {
                item(key = "catalogue-empty") {
                    AirCatAeroEmptyState(
                        title = if (state.query.isBlank()) "Este catálogo está vacío" else "Sin coincidencias",
                        detail = if (state.query.isBlank()) "Este equipo no ofrece pistas para recibir." else "Prueba otra búsqueda. Tus selecciones no se perderán.",
                        primaryLabel = "Limpiar búsqueda",
                        onPrimary = { onIntent(AirCatIntent.QueryChanged("")) },
                    )
                }
            }
        }
        item(key = "catalogue-footer") { SelectionFooter(state, onIntent) }
    }
}

@Composable
private fun AlbumHeader(album: AirCatAlbumUi, selection: Set<String>, onIntent: (AirCatIntent) -> Unit) {
    val selected = album.tracks.count { selection.contains(it.remoteTrackId) }
    val state = when {
        selected == 0 -> ToggleableState.Off
        selected == album.tracks.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Row(
        // The row is the target: 48dp tall and toggleable, so the checkbox is a
        // visual indicator instead of a 24dp hit area. TalkBack announces the
        // album and its tri-state from the row itself.
        modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(org.michimusic.mobile.ui.theme.GlassFillLow)
            .triStateToggleable(
                state = state,
                onClick = { onIntent(AirCatIntent.ToggleAlbum(album.album)) },
                role = Role.Checkbox,
            )
            .testTag("aircat_album_${album.album}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = state,
            onClick = null,
        )
        Column(Modifier.weight(1f)) {
            Text(album.album, color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${album.trackCount} pistas · ${airCatHumanBytes(album.totalBytes)}",
                color = TextSecondary, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TrackRow(track: AirCatTrackUi, checked: Boolean, onIntent: (AirCatIntent) -> Unit) {
    Row(
        // Same contract as the album header: the whole 48dp row is the target.
        modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(org.michimusic.mobile.ui.theme.GlassFillLow.copy(alpha = 0.64f))
            .toggleable(
                value = checked,
                onValueChange = { onIntent(AirCatIntent.ToggleTrack(track.remoteTrackId)) },
                role = Role.Checkbox,
            )
            .testTag("aircat_track_selection"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One testTag per node: a second one on the row would be ignored (the
        // first wins), so the remote identity lives on the checkbox.
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.testTag("aircat_track_${track.remoteTrackId}"),
        )
        Column(Modifier.weight(1f)) {
            Text(track.title, color = PureWhite, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    track.artist.takeIf { it.isNotBlank() },
                    track.album.takeIf { it.isNotBlank() },
                    track.format.uppercase().takeIf { it.isNotBlank() },
                    airCatHumanBytes(track.size),
                ).joinToString(" · "),
                color = TextSecondary, fontSize = 12.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SelectionFooter(state: AirCatExperienceState, onIntent: (AirCatIntent) -> Unit) {
    AeroSurface(role = AeroSurfaceRole.Raised, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(MichiSpacing.lg).testTag("aircat_selection_footer")) {
            Text(
                airCatSelectionSummary(state.selectedTracks.size, state.selectedBytes),
                color = if (state.selection.isEmpty()) TextSecondary else PureWhite,
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(MichiSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
                Button(
                    onClick = { onIntent(AirCatIntent.Review) },
                    enabled = state.canReview,
                    modifier = Modifier.weight(1f).heightIn(min = MichiSpacing.minTouchTarget),
                ) { Text("Continuar", color = SurfaceObsidian) }
                TextButton(
                    onClick = { onIntent(AirCatIntent.Back) },
                    modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget),
                ) { Text("Volver") }
            }
            state.message?.let { NoticeText(it) }
        }
    }
}

// --- Review ----------------------------------------------------------------

@Composable
private fun AirCatReviewContent(state: AirCatExperienceState, onIntent: (AirCatIntent) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("aircat_review_screen"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(screenPadding),
        verticalArrangement = Arrangement.spacedBy(blockGap),
    ) {
        item {
            AirCatAeroSectionTitle("Confirma antes de recibir",
                "Solo estas canciones serán autorizadas para el equipo seleccionado.")
        }
        item {
            AeroSurface(role = AeroSurfaceRole.Content, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(MichiSpacing.lg)) {
                    // The source is the device being read from. Calling it
                    // "destination" was the defect: this phone receives.
                    AirCatAeroRouteFlow(
                        origin = state.selectedSource?.name ?: "Origen musical",
                        destination = "Este teléfono · Biblioteca sincronizada",
                    )
                    Spacer(Modifier.height(MichiSpacing.md))
                    Text(
                        airCatSelectionSummary(state.selectedTracks.size, state.selectedBytes),
                        color = PureWhite, fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(MichiSpacing.sm))
                    AirCatAeroFact("Formato original · SHA-256 al recibir · sin transcodificación")
                    if (state.availableBytes == null) {
                        Text(if (state.storageProbeFailed) "No se pudo comprobar el almacenamiento. Vuelve a revisar." else "Comprobando espacio disponible…",
                            color = TextSecondary, fontSize = 12.sp,
                            modifier = Modifier.testTag("aircat_storage_checking"))
                    } else {
                        Text("Espacio disponible: ${airCatHumanBytes(state.availableBytes)}",
                            color = TextSecondary, fontSize = 12.sp)
                    }
                    Text("El cálculo incluye la reserva de seguridad de 100 MiB de AirCat.",
                        color = TextSecondary, fontSize = 12.sp)
                    state.storageRefusal?.let {
                        Spacer(Modifier.height(MichiSpacing.sm))
                        Text(it, color = PrimaryPinkContainer, fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            Button(
                onClick = { onIntent(AirCatIntent.Confirm) },
                enabled = state.canConfirm,
                modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)
                    .testTag("aircat_confirm_button"),
            ) { Text("Confirmar y recibir", color = SurfaceObsidian, fontWeight = FontWeight.SemiBold) }
        }
        item {
            TextButton(
                onClick = { onIntent(AirCatIntent.Back) },
                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget),
            ) { Text("Volver a selección") }
            if (state.storageProbeFailed) {
                TextButton(onClick = { onIntent(AirCatIntent.Review) },
                    modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)) {
                    Text("Reintentar comprobación de espacio", color = TertiaryCyan)
                }
            }
        }
        state.message?.let { item { NoticeText(it) } }
    }
}

// --- Transfer --------------------------------------------------------------

@Composable
private fun AirCatTransferContent(state: AirCatExperienceState, onIntent: (AirCatIntent) -> Unit) {
    val transfer = state.transfer
    var cancelRequested by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("aircat_transfer_details"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(screenPadding),
        verticalArrangement = Arrangement.spacedBy(blockGap),
    ) {
        item {
            AirCatAeroSectionTitle("Copia de archivos originales",
                "El porcentaje procede del motor. La verificación solo se declara al terminar.")
            transfer?.let { event ->
                Spacer(Modifier.height(MichiSpacing.sm))
                AirCatAeroFact(when (event.state) {
                    AirCatTransferState.PENDING_AUTHORIZATION -> "Solicitando autorización Home"
                    AirCatTransferState.NEGOTIATING -> "Negociando transporte seguro"
                    AirCatTransferState.TRANSFERRING -> "Recibiendo archivos originales"
                    AirCatTransferState.VERIFYING -> "Verificando integridad y biblioteca"
                    AirCatTransferState.PAUSED -> "Transferencia pausada"
                    AirCatTransferState.CANCELLED -> "Transferencia cancelada"
                    AirCatTransferState.COMPLETED -> "Transferencia completada y certificada"
                    AirCatTransferState.NO_FILES -> "No había archivos para transferir"
                    AirCatTransferState.FAILED -> "Recepción interrumpida"
                })
            }
        }
        item {
            AeroSurface(role = AeroSurfaceRole.Content, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(MichiSpacing.lg)) {
                    Text(
                        "${state.selectedSource?.name ?: "Origen"} → Este teléfono",
                        color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(MichiSpacing.sm))
                    if (transfer == null) {
                        CircularProgressIndicator(color = TertiaryCyan)
                        Text("Preparando autorización y sesión segura…", color = TextSecondary, fontSize = 13.sp)
                    } else {
                        // Only facts the engine actually reported.
                        Text(
                            "${transfer.completedTracks} de ${transfer.totalTracks} canciones",
                            color = PureWhite, fontSize = 14.sp,
                        )
                        Text(
                            "${airCatHumanBytes(transfer.transferredBytes)} de " +
                                airCatHumanBytes(transfer.totalBytes),
                            color = TextSecondary, fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(MichiSpacing.sm))
                        LinearProgress(transfer.fraction)
                        transfer.currentTrackTitle?.let {
                            Spacer(Modifier.height(MichiSpacing.sm))
                            Text("Archivo actual: $it", color = TextSecondary, fontSize = 12.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        transfer.error?.let {
                            Spacer(Modifier.height(MichiSpacing.sm))
                            Text(airCatFailureMessage(it), color = PrimaryPinkContainer, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
                val paused = state.transfer?.paused == true
                Button(
                    onClick = {
                        onIntent(if (paused) AirCatIntent.Resume else AirCatIntent.Pause)
                    },
                    enabled = state.transfer != null,
                    modifier = Modifier.weight(1f).heightIn(min = MichiSpacing.minTouchTarget),
                ) { Text(if (paused) "Reanudar" else "Pausar", color = SurfaceObsidian) }
                TextButton(
                    onClick = { cancelRequested = true },
                    modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget),
                ) { Text("Cancelar", color = PrimaryPinkContainer) }
            }
        }
        if (cancelRequested) item {
            AeroSurface(role = AeroSurfaceRole.Raised,
                modifier = Modifier.fillMaxWidth().testTag("aircat_cancel_confirmation")) {
                Column(Modifier.padding(MichiSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
                    Text("¿Detener la recepción de música?", color = PureWhite,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("La música ya incorporada se conserva. Podrás revisar la recuperación " +
                        "si el motor guardó un checkpoint válido.",
                        color = TextSecondary, fontSize = 13.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(MichiSpacing.xs)) {
                        TextButton(onClick = { cancelRequested = false },
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = MichiSpacing.minTouchTarget)) {
                            Text("Seguir recibiendo", color = TertiaryCyan)
                        }
                        TextButton(onClick = {
                            cancelRequested = false
                            onIntent(AirCatIntent.Cancel)
                        }, modifier = Modifier.fillMaxWidth()
                            .heightIn(min = MichiSpacing.minTouchTarget)) {
                            Text("Detener recepción", color = PrimaryPinkContainer)
                        }
                    }
                }
            }
        }
        if (state.transfer != null && !state.transfer.isFinished) item(key = "refresh_evidence") {
            TextButton(onClick = { onIntent(AirCatIntent.RefreshEvidence) },
                modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)) {
                Text("Consultar registros de archivos", color = TertiaryCyan)
            }
        }
        if (state.selectedTracks.isNotEmpty()) item(key = "aircat_batch_preview") {
            AirCatBatchPreview(state.selectedTracks, state.fileEvidence)
        }
        state.message?.let { item { NoticeText(it) } }
    }
}

@Composable
private fun LinearProgress(fraction: Float) {
    AirCatAeroProgress(fraction)
}

// --- Result ----------------------------------------------------------------

@Composable
private fun AirCatResultContent(state: AirCatExperienceState, onIntent: (AirCatIntent) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("aircat_result_screen"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(screenPadding),
        verticalArrangement = Arrangement.spacedBy(blockGap),
    ) {
        item {
            val result = state.result
            AeroSurface(role = AeroSurfaceRole.Content, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(MichiSpacing.lg)) {
                    result?.let { AirCatAeroOutcome(it.kind) }
                    if (state.fileEvidenceUnavailable) {
                        Spacer(Modifier.height(MichiSpacing.sm))
                        NoticeText("No pudimos leer los registros locales de archivos. " +
                            "No se muestran estados por archivo.")
                    } else if (state.fileEvidence.isNotEmpty()) {
                        Spacer(Modifier.height(MichiSpacing.sm))
                        AirCatAeroCheckpointSummary(state.fileEvidence)
                    }
                    Spacer(Modifier.height(MichiSpacing.sm))
                    if (result?.kind == AirCatResultUi.Kind.FAILED ||
                        result?.kind == AirCatResultUi.Kind.UNCERTIFIED) {
                        AirCatAeroFact("La reparación local y la reanudación por red " +
                            "son operaciones diferentes; consulta Devices antes de reintentar.")
                        Spacer(Modifier.height(MichiSpacing.sm))
                    }
                    AirCatAeroSectionTitle(
                        result?.title ?: "Resultado de transferencia",
                        when (result?.kind) {
                            AirCatResultUi.Kind.COMPLETED -> "Archivos recibidos, verificados e incorporados"
                            AirCatResultUi.Kind.NO_FILES -> "No se descargaron archivos"
                            AirCatResultUi.Kind.CANCELLED -> "Operación detenida por el usuario"
                            AirCatResultUi.Kind.FAILED -> "Revisa el motivo y la recuperación en Devices"
                            AirCatResultUi.Kind.UNCERTIFIED -> "No se recibió comprobante final; no se puede asegurar éxito"
                            null -> null
                        },
                    )
                    result?.detail?.let {
                        Spacer(Modifier.height(MichiSpacing.sm))
                        Text(it, color = TextSecondary, fontSize = 13.sp)
                    }
                    result?.let {
                        if (it.tracks > 0) {
                            Spacer(Modifier.height(MichiSpacing.sm))
                            Text(
                                "${it.tracks} canciones · ${airCatHumanBytes(it.bytes)}",
                                color = TextSecondary, fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = { onIntent(AirCatIntent.DismissResult) },
                modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)
                    .testTag("aircat_result_close"),
            ) { Text("Cerrar", color = SurfaceObsidian) }
        }
    }
}

// --- Shared pieces ---------------------------------------------------------

@Composable
private fun ActiveTransferCard(transfer: AirCatTransferUi, onIntent: (AirCatIntent) -> Unit) {
    AeroSurface(role = AeroSurfaceRole.Raised, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(MichiSpacing.lg)) {
            Text(
                "Transferencia en curso",
                color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${transfer.completedTracks} de ${transfer.totalTracks} canciones · " +
                    airCatHumanBytes(transfer.transferredBytes),
                color = TextSecondary, fontSize = 13.sp,
            )
            Spacer(Modifier.height(MichiSpacing.sm))
            TextButton(
                onClick = { onIntent(AirCatIntent.OpenActiveTransfer) },
                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget),
            ) { Text("Ver detalles") }
        }
    }
}

@Composable
private fun ResultCard(result: AirCatResultUi, onIntent: (AirCatIntent) -> Unit) {
    AeroSurface(role = AeroSurfaceRole.Content, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(MichiSpacing.lg)) {
            Text(result.title, color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(result.detail, color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NoticeText(message: String) {
    Text(
        message,
        color = TextSecondary,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = MichiSpacing.xs).testTag("aircat_notice"),
    )
}

/**
 * A71-09: trust administration, moved out of the overflow menu and out of a
 * chain of dialogs. It renders [MichiLinkTrustState] only; every read happens in
 * [MichiLinkTrustHost] and every revocation stays a two-step action owned by
 * [MichiLinkTrustController].
 */
@Composable
internal fun MichiLinkTrustContent(
    state: MichiLinkTrustState,
    onIntent: (MichiLinkTrustIntent) -> Unit,
    onLinkHome: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
    AeroPremiumBackdrop(accent = TertiaryCyan)
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("michi_link_trust_screen"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(screenPadding),
        verticalArrangement = Arrangement.spacedBy(blockGap),
    ) {
        item {
            AirCatAeroHeader(
                title = "Confianza Home",
                eyebrow = "Michi Link · Seguridad",
                subtitle = "Gestiona los equipos que pueden compartir tu biblioteca",
                onBack = onClose,
            )
        }
        item {
            AirCatAeroHero(
                title = "Protección de transferencias",
                detail = "Home acredita el origen y autoriza cada lote. Revocar un equipo " +
                    "no elimina la música ya recibida.",
                actionLabel = "Vincular nuevo Home",
                actionTag = "michi_link_trust_link_home",
                onAction = onLinkHome,
                icon = Icons.Filled.AddLink,
            )
        }

        state.notice?.let { notice ->
            item { TrustNoticeCard(notice, onIntent) }
        }

        state.pending?.let { pending ->
            item { TrustConfirmationCard(pending, busy = state.busy, onIntent = onIntent) }
        }

        when {
            state.loading -> item { TrustMessage("Leyendo vínculos locales cifrados…", "michi_link_trust_loading") }
            state.loadFailure != null -> item { TrustReadFailure(onIntent) }
            else -> {
                if (!state.hasInventory) {
                    item {
                        TrustMessage(
                            "Todavía no hay autoridades Home. Vincula el QR administrativo " +
                                "de tu propio Home para autorizar orígenes musicales.",
                            "michi_link_trust_empty",
                        )
                    }
                }
                if (state.homes.isNotEmpty()) {
                    item { TrustSectionTitle("Autoridades Home") }
                    items(state.homes, key = { it.homeId }) { home ->
                        TrustHomeCard(home, enabled = state.canRequest, onIntent = onIntent)
                    }
                }
                if (state.sources.isNotEmpty()) {
                    item { TrustSectionTitle("Orígenes autorizados") }
                    items(state.sources, key = { it.sourceMichiId }) { source ->
                        TrustSourceCard(source, enabled = state.canRequest, onIntent = onIntent)
                    }
                }
            }
        }

        // The prominent Aero hero above is the single Home enrollment CTA.
        item {
            TextButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)
                    .testTag("michi_link_trust_close"),
            ) { Text("Cerrar") }
        }
    }
    }
}

@Composable
private fun TrustSectionTitle(text: String) {
    Text(
        text,
        color = PureWhite,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun TrustMessage(message: String, tag: String) {
    Text(
        message,
        color = TextSecondary,
        fontSize = 13.sp,
        modifier = Modifier.testTag(tag),
    )
}

/** One Home root, with the exact source count and bindings digest it covers. */
@Composable
private fun TrustHomeCard(
    home: org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeAuthority,
    enabled: Boolean,
    onIntent: (MichiLinkTrustIntent) -> Unit,
) {
    val noun = if (home.sourceCount == 1) "origen registrado" else "orígenes registrados"
    AeroSurface(
        role = AeroSurfaceRole.Content,
        modifier = Modifier.fillMaxWidth().testTag("michi_link_trust_home_${home.homeId}"),
    ) {
        Column(Modifier.padding(MichiSpacing.lg)) {
            Text(
                "Home ${airCatShortId(home.homeId)}",
                color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Autoridad: ${airCatShortId(home.authorityMichiId)}",
                color = TextSecondary, fontSize = 12.sp,
            )
            Text(
                "${home.sourceCount} $noun",
                color = TextSecondary, fontSize = 12.sp,
            )
            Text(
                "Huella de vínculos: ${airCatShortDigest(home.sourceBindingsSha256)}",
                color = TextSecondary, fontSize = 12.sp,
            )
            TextButton(
                onClick = { onIntent(MichiLinkTrustIntent.RequestHomeRevocation(home)) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                    .testTag("michi_link_trust_home_revoke_${home.homeId}"),
            ) { Text("Desvincular este Home", color = PrimaryPinkContainer) }
        }
    }
}

/** One authorised source binding, with the endpoint this phone pinned for it. */
@Composable
private fun TrustSourceCard(
    source: org.michimusic.mobile.devices.aircat.AirCatEnrolledHomeSource,
    enabled: Boolean,
    onIntent: (MichiLinkTrustIntent) -> Unit,
) {
    AeroSurface(
        role = AeroSurfaceRole.Content,
        modifier = Modifier.fillMaxWidth().testTag("michi_link_trust_source_${source.sourceMichiId}"),
    ) {
        Column(Modifier.padding(MichiSpacing.lg)) {
            Text(
                "Origen ${airCatShortId(source.sourceMichiId)}",
                color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Text("Endpoint fijado: ${source.pinnedBaseUrl}",
                color = TextSecondary, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "Home: ${airCatShortId(source.homeId)}",
                color = TextSecondary, fontSize = 12.sp,
            )
            Text(
                "Huella TLS: ${airCatShortDigest(source.tlsSpkiSha256)}",
                color = TextSecondary, fontSize = 12.sp,
            )
            TextButton(
                onClick = { onIntent(MichiLinkTrustIntent.RequestSourceRevocation(source)) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                    .testTag("michi_link_trust_source_revoke_${source.sourceMichiId}"),
            ) { Text("Desvincular", color = PrimaryPinkContainer) }
        }
    }
}

/** The second step: the exact impact, then a separate confirmation. */
@Composable
private fun TrustConfirmationCard(
    pending: MichiLinkTrustPending,
    busy: Boolean,
    onIntent: (MichiLinkTrustIntent) -> Unit,
) {
    AeroSurface(
        role = AeroSurfaceRole.Raised,
        modifier = Modifier.fillMaxWidth().testTag("michi_link_trust_confirmation"),
    ) {
        Column(Modifier.padding(MichiSpacing.lg)) {
            Text(
                "Confirmar desvinculación",
                color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(MichiSpacing.sm))
            Text(
                pending.impact,
                color = TextSecondary, fontSize = 13.sp,
                modifier = Modifier.testTag("michi_link_trust_impact"),
            )
            Spacer(Modifier.height(MichiSpacing.sm))
            Row(
                horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onIntent(MichiLinkTrustIntent.ConfirmRevocation) },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                        .testTag("michi_link_trust_confirm"),
                ) { Text("Desvincular", color = SurfaceObsidian, fontWeight = FontWeight.SemiBold) }
                TextButton(
                    onClick = { onIntent(MichiLinkTrustIntent.CancelRevocation) },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                        .testTag("michi_link_trust_cancel"),
                ) { Text("Conservar") }
            }
        }
    }
}

/** The persisted outcome, told apart from a stale snapshot and a failed commit. */
@Composable
private fun TrustNoticeCard(
    notice: MichiLinkTrustNotice,
    onIntent: (MichiLinkTrustIntent) -> Unit,
) {
    AeroSurface(
        role = AeroSurfaceRole.Raised,
        modifier = Modifier.fillMaxWidth().testTag("michi_link_trust_notice"),
    ) {
        Column(Modifier.padding(MichiSpacing.lg)) {
            Text(notice.message, color = PureWhite, fontSize = 13.sp)
            TextButton(
                onClick = { onIntent(MichiLinkTrustIntent.DismissNotice) },
                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget),
            ) { Text("Descartar") }
        }
    }
}

/** Encrypted inventory unreadable: no row may be drawn from a stale snapshot. */
@Composable
private fun TrustReadFailure(onIntent: (MichiLinkTrustIntent) -> Unit) {
    AeroSurface(
        role = AeroSurfaceRole.Content,
        modifier = Modifier.fillMaxWidth().testTag("michi_link_trust_read_failed"),
    ) {
        Column(Modifier.padding(MichiSpacing.lg)) {
            Text(
                "No pudimos leer la confianza Home cifrada (Keystore). " +
                    "No se modificó ninguna autorización.",
                color = PureWhite, fontSize = 13.sp,
            )
            TextButton(
                onClick = { onIntent(MichiLinkTrustIntent.Refresh) },
                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                    .testTag("michi_link_trust_retry"),
            ) { Text("Reintentar") }
        }
    }
}
