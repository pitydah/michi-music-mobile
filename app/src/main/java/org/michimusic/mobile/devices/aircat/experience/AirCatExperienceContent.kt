package org.michimusic.mobile.devices.aircat.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.ui.components.AeroSurface
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
) {
    Box(modifier = modifier.fillMaxSize().testTag("aircat_experience")) {
        when (state.phase) {
            AirCatPhase.HUB -> AirCatHubContent(state, onIntent)
            AirCatPhase.SOURCES -> AirCatSourcePickerContent(state, onIntent)
            AirCatPhase.CATALOGUE -> AirCatCatalogueContent(state, onIntent)
            AirCatPhase.REVIEW -> AirCatReviewContent(state, onIntent)
            AirCatPhase.TRANSFER -> AirCatTransferContent(state, onIntent)
            AirCatPhase.RESULT -> AirCatResultContent(state, onIntent)
        }
    }
}

// --- Hub -------------------------------------------------------------------

@Composable
private fun AirCatHubContent(state: AirCatExperienceState, onIntent: (AirCatIntent) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("aircat_hub_screen"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(screenPadding),
        verticalArrangement = Arrangement.spacedBy(blockGap),
    ) {
        item {
            Column {
                Text("AirCat", style = MichiTypography.screenEyebrowTechnical)
                Spacer(Modifier.height(MichiSpacing.xs))
                Text(
                    "Transfiere música original entre equipos Michi de confianza.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                )
            }
        }

        item {
            Button(
                onClick = { onIntent(AirCatIntent.OpenSources) },
                modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)
                    .testTag("aircat_receive_button"),
            ) {
                Text("Recibir música", color = SurfaceObsidian, fontWeight = FontWeight.SemiBold)
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
                        Text(
                            "No encontramos equipos disponibles para recibir música.",
                            color = TextSecondary, fontSize = 13.sp,
                        )
                    } else {
                        state.sources.forEach { source ->
                            Text("${source.name} · ${source.statusLine}",
                                color = TextSecondary, fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 2.dp))
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
private fun AirCatSourcePickerContent(state: AirCatExperienceState, onIntent: (AirCatIntent) -> Unit) {
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
                NoticeText("No encontramos equipos disponibles para recibir música.")
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
                    Text(source.statusLine, color = TextSecondary, fontSize = 13.sp)
                    Spacer(Modifier.height(MichiSpacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
                        if (source.canExplore) {
                            TextButton(
                                onClick = { onIntent(AirCatIntent.ChooseSource(source.deviceId)) },
                                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                                    .testTag("aircat_explore_${source.deviceId}"),
                            ) { Text("Explorar música") }
                        } else if (source.needsHome) {
                            Text(
                                "Vincula este equipo a Home para recibir música.",
                                color = TextSecondary, fontSize = 12.sp,
                            )
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
    Column(Modifier.fillMaxSize().testTag("aircat_catalog_screen")) {
        Column(Modifier.padding(horizontal = screenPadding, vertical = MichiSpacing.md)) {
            Text(
                "Música de ${state.selectedSource?.name ?: "…"}",
                color = PureWhite, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Selecciona las canciones y álbumes que quieres recibir.",
                color = TextSecondary, fontSize = 13.sp,
            )
            Spacer(Modifier.height(MichiSpacing.sm))
            OutlinedTextField(
                value = state.query,
                onValueChange = { onIntent(AirCatIntent.QueryChanged(it)) },
                singleLine = true,
                label = { Text("Filtrar por título, artista o álbum") },
                modifier = Modifier.fillMaxWidth().testTag("aircat_catalog_query"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
                TextButton(onClick = { onIntent(AirCatIntent.SelectAll) }) { Text("Seleccionar todo") }
                TextButton(onClick = { onIntent(AirCatIntent.ClearSelection) }) { Text("Limpiar") }
            }
        }

        Box(Modifier.weight(1f)) {
            if (state.catalogueLoading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = TertiaryCyan) }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = screenPadding, end = screenPadding, bottom = MichiSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm),
                ) {
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
                        item {
                            NoticeText("No hay pistas que coincidan con el filtro.")
                        }
                    }
                }
            }
        }

        SelectionFooter(state, onIntent)
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
        modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)
            .testTag("aircat_album_${album.album}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = state,
            onClick = { onIntent(AirCatIntent.ToggleAlbum(album.album)) },
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
        modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)
            .testTag("aircat_track_selection")
            .testTag("aircat_track_${track.remoteTrackId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onIntent(AirCatIntent.ToggleTrack(track.remoteTrackId)) },
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
            Text("Revisar transferencia", style = MichiTypography.screenEyebrowTechnical)
        }
        item {
            AeroSurface(role = AeroSurfaceRole.Content, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(MichiSpacing.lg)) {
                    // The source is the device being read from. Calling it
                    // "destination" was the defect: this phone receives.
                    Text("Origen", color = TextSecondary, fontSize = 12.sp)
                    Text(state.selectedSource?.name ?: "…", color = PureWhite, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(MichiSpacing.md))
                    Text("Destino", color = TextSecondary, fontSize = 12.sp)
                    Text("Este teléfono · Biblioteca sincronizada",
                        color = PureWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(MichiSpacing.md))
                    Text(
                        airCatSelectionSummary(state.selectedTracks.size, state.selectedBytes),
                        color = PureWhite, fontSize = 14.sp,
                    )
                    Text("Formato original · sin conversión de audio",
                        color = TextSecondary, fontSize = 12.sp)
                    state.availableBytes?.let { free ->
                        Text("Espacio disponible: ${airCatHumanBytes(free)}",
                            color = TextSecondary, fontSize = 12.sp)
                    }
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
                enabled = state.canReview && state.storageRefusal == null && !state.starting,
                modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget)
                    .testTag("aircat_confirm_button"),
            ) { Text("Confirmar y recibir", color = SurfaceObsidian, fontWeight = FontWeight.SemiBold) }
        }
        item {
            TextButton(
                onClick = { onIntent(AirCatIntent.Back) },
                modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget),
            ) { Text("Volver a selección") }
        }
        state.message?.let { item { NoticeText(it) } }
    }
}

// --- Transfer --------------------------------------------------------------

@Composable
private fun AirCatTransferContent(state: AirCatExperienceState, onIntent: (AirCatIntent) -> Unit) {
    val transfer = state.transfer
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("aircat_transfer_details"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(screenPadding),
        verticalArrangement = Arrangement.spacedBy(blockGap),
    ) {
        item {
            Text("Recibiendo música", style = MichiTypography.screenEyebrowTechnical)
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
                        Text("Preparando la transferencia…", color = TextSecondary, fontSize = 13.sp)
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
                    onClick = { onIntent(AirCatIntent.Cancel) },
                    modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget),
                ) { Text("Cancelar") }
            }
        }
        state.message?.let { item { NoticeText(it) } }
    }
}

@Composable
private fun LinearProgress(fraction: Float) {
    Box(
        Modifier.fillMaxWidth().height(6.dp)
            .padding(top = 0.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(6.dp)
                .testTag("aircat_transfer_fraction"),
        ) { }
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp)
                .testTag("aircat_transfer_bar"),
        ) { }
    }
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
                    Text(result?.title ?: "", color = PureWhite, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold)
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
                onClick = { onIntent(AirCatIntent.RetryCatalogue) },
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
 * chain of dialogs. It renders state only; revoking remains a two-step action
 * owned by the caller's use case.
 */
@Composable
internal fun MichiLinkTrustContent(
    homeId: String?,
    sources: List<AirCatSourceUi>,
    onRevokeSource: (String) -> Unit,
    onLinkHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("michi_link_trust_screen"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(screenPadding),
        verticalArrangement = Arrangement.spacedBy(blockGap),
    ) {
        item {
            Column {
                Text("Administrar confianza", style = MichiTypography.screenEyebrowTechnical)
                Spacer(Modifier.height(MichiSpacing.xs))
                Text(
                    "Home es la autoridad que firma las transferencias AirCat. Revocar un origen no borra la música ya recibida.",
                    color = TextSecondary, fontSize = 13.sp,
                )
            }
        }
        item {
            AeroSurface(role = AeroSurfaceRole.Content, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(MichiSpacing.lg)) {
                    Text("Autoridad Home", color = PureWhite, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        homeId?.take(12)?.plus("…") ?: "Sin Home vinculada",
                        color = TextSecondary, fontSize = 13.sp,
                    )
                    Text("${sources.size} orígenes autorizados",
                        color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
        items(sources, key = { it.deviceId }) { source ->
            AeroSurface(role = AeroSurfaceRole.Content, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(MichiSpacing.lg)) {
                    Text(source.name, color = PureWhite, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(source.statusLine, color = TextSecondary, fontSize = 12.sp)
                    TextButton(
                        onClick = { onRevokeSource(source.deviceId) },
                        modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget),
                    ) { Text("Desvincular", color = PrimaryPinkContainer) }
                }
            }
        }
        item {
            Button(
                onClick = onLinkHome,
                modifier = Modifier.fillMaxWidth().heightIn(min = MichiSpacing.minTouchTarget),
            ) { Text("Vincular nuevo Home", color = SurfaceObsidian) }
        }
    }
}
