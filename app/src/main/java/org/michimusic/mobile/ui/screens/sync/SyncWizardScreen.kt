package org.michimusic.mobile.ui.screens.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.core.models.DeliveryProfile
import org.michimusic.core.models.IssueSeverity
import org.michimusic.core.models.ManifestPlaylist
import org.michimusic.core.models.ManifestTrack
import org.michimusic.core.models.ProfileAvailability
import org.michimusic.core.models.SyncItemAction
import org.michimusic.core.models.SyncPlan
import org.michimusic.core.models.SyncScope
import org.michimusic.core.models.SyncSelection
import org.michimusic.core.sync.StoragePreflightEvaluator
import org.michimusic.core.sync.SyncPreflightService
import org.michimusic.core.sync.SyncProfileNegotiator
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.ErrorColor
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

enum class SyncWizardStep {
    SELECTION,
    QUALITY,
    REVIEW
}

@Composable
fun SyncWizardScreen(
    endpointId: String,
    endpointName: String,
    availableStorageBytes: Long,
    totalStorageBytes: Long,
    currentLocalMusicBytes: Long,
    candidateTracks: List<ManifestTrack>,
    manifestPlaylists: List<ManifestPlaylist> = emptyList(),
    serverCapabilities: Set<String> = emptySet(),
    serverRepresentations: List<String> = emptyList(),
    localFingerprints: Set<String> = emptySet(),
    existingPartials: Map<String, Long> = emptyMap(),
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    onStartSync: (SyncPlan) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the wizard's choices when Android recreates the Activity. These are
    // UI selections only; a sync can start only after the fresh preflight passes.
    var currentStep by rememberSaveable(endpointId) { mutableStateOf(SyncWizardStep.SELECTION) }
    var selectedScope by rememberSaveable(endpointId) { mutableStateOf(SyncScope.FULL_LIBRARY) }
    var selectedAlbumIds by rememberSaveable(endpointId) { mutableStateOf<Set<String>>(emptySet()) }
    var selectedArtistIds by rememberSaveable(endpointId) { mutableStateOf<Set<String>>(emptySet()) }
    var selectedPlaylistIds by rememberSaveable(endpointId) { mutableStateOf<Set<String>>(emptySet()) }
    var selectedTrackIds by rememberSaveable(endpointId) { mutableStateOf<Set<String>>(emptySet()) }
    var selectedProfile by rememberSaveable(endpointId) { mutableStateOf(DeliveryProfile.ORIGINAL) }
    val scrollState = rememberScrollState()

    androidx.compose.runtime.LaunchedEffect(currentStep) {
        scrollState.scrollTo(0)
    }

    val availableProfiles = remember(serverCapabilities, serverRepresentations) {
        SyncProfileNegotiator.evaluateAvailableProfiles(serverCapabilities, serverRepresentations)
    }

    val preflightService = remember { SyncPreflightService() }

    val currentSelection = remember(selectedScope, selectedAlbumIds, selectedArtistIds, selectedPlaylistIds, selectedTrackIds) {
        SyncSelection(
            scope = selectedScope,
            albumIds = selectedAlbumIds,
            artistIds = selectedArtistIds,
            playlistIds = selectedPlaylistIds,
            trackIds = selectedTrackIds,
        )
    }

    val deliveryChoice = remember(selectedProfile, serverCapabilities, serverRepresentations) {
        SyncProfileNegotiator.resolveDeliveryChoice(selectedProfile, serverCapabilities, serverRepresentations)
    }

    val syncPlan = remember(currentSelection, deliveryChoice, availableStorageBytes, candidateTracks, manifestPlaylists, localFingerprints, existingPartials) {
        preflightService.buildPlan(
            endpointId = endpointId,
            endpointName = endpointName,
            selection = currentSelection,
            deliveryChoice = deliveryChoice,
            availableStorageBytes = availableStorageBytes,
            candidateTracks = candidateTracks,
            manifestPlaylists = manifestPlaylists,
            localTrackFingerprints = localFingerprints,
            existingPartials = existingPartials,
        )
    }

    Scaffold(
        containerColor = SurfaceObsidian,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        when (currentStep) {
                            SyncWizardStep.SELECTION -> onBack()
                            SyncWizardStep.QUALITY -> currentStep = SyncWizardStep.SELECTION
                            SyncWizardStep.REVIEW -> currentStep = SyncWizardStep.QUALITY
                        }
                    },
                    modifier = Modifier.size(48.dp).testTag("sync_wizard_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = PureWhite
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Asistente de Sincronización",
                        style = MichiTypography.sectionTitle,
                        color = PureWhite,
                        fontSize = 18.sp,
                    )
                    Text(
                        text = "Nodo: $endpointName",
                        style = MichiTypography.metadata,
                        color = TertiaryCyan,
                    )
                }
            }
        },
        modifier = modifier.testTag("sync_wizard_screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Step Indicator Header
            StepProgressHeader(currentStep = currentStep)

            Spacer(modifier = Modifier.height(20.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp)
                        .testTag("sync_wizard_loading"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = AccentCoral)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Obteniendo catálogo del servidor...",
                            style = MichiTypography.cardTitle,
                            color = PureWhite
                        )
                    }
                }
            } else if (errorMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MichiShapes.md)
                        .border(1.dp, ErrorColor, MichiShapes.md)
                        .testTag("sync_wizard_error"),
                    colors = CardDefaults.cardColors(containerColor = GlassFillHigh)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Error, contentDescription = null, tint = ErrorColor)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Error al conectar con el catálogo", style = MichiTypography.cardTitle, color = ErrorColor)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = errorMessage, style = MichiTypography.metadata, color = TextSecondary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = org.michimusic.mobile.ui.theme.PrimaryPinkContainer, contentColor = PureWhite),
                            modifier = Modifier.testTag("sync_wizard_retry_button")
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reintentar")
                        }
                    }
                }
            } else {
                when (currentStep) {
                    SyncWizardStep.SELECTION -> {
                        SelectionStep(
                            selectedScope = selectedScope,
                            totalCandidates = candidateTracks.size,
                            candidateTracks = candidateTracks,
                            manifestPlaylists = manifestPlaylists,
                            selectedAlbumIds = selectedAlbumIds,
                            selectedArtistIds = selectedArtistIds,
                            selectedPlaylistIds = selectedPlaylistIds,
                            selectedTrackIds = selectedTrackIds,
                            totalSelectedCount = syncPlan.totalSelectedTracks,
                            totalSelectedBytes = syncPlan.totalDownloadBytes,
                            onSelectScope = { selectedScope = it },
                            onToggleAlbum = { album ->
                                selectedAlbumIds = if (album in selectedAlbumIds) selectedAlbumIds - album else selectedAlbumIds + album
                            },
                            onToggleArtist = { artist ->
                                selectedArtistIds = if (artist in selectedArtistIds) selectedArtistIds - artist else selectedArtistIds + artist
                            },
                            onTogglePlaylist = { plId ->
                                selectedPlaylistIds = if (plId in selectedPlaylistIds) selectedPlaylistIds - plId else selectedPlaylistIds + plId
                            },
                            onToggleTrack = { trkId ->
                                selectedTrackIds = if (trkId in selectedTrackIds) selectedTrackIds - trkId else selectedTrackIds + trkId
                            },
                            onSelectAllAlbums = {
                                selectedAlbumIds = candidateTracks.map { it.album.ifEmpty { "Desconocido" } }.toSet()
                            },
                            onClearAllAlbums = { selectedAlbumIds = emptySet() },
                            onSelectAllArtists = {
                                selectedArtistIds = candidateTracks.map { it.artist.ifEmpty { "Desconocido" } }.toSet()
                            },
                            onClearAllArtists = { selectedArtistIds = emptySet() },
                            onSelectAllPlaylists = {
                                selectedPlaylistIds = manifestPlaylists.map { it.playlistId }.toSet()
                            },
                            onClearAllPlaylists = { selectedPlaylistIds = emptySet() },
                            onSelectAllTracks = {
                                selectedTrackIds = candidateTracks.map { it.trackId }.toSet()
                            },
                            onClearAllTracks = { selectedTrackIds = emptySet() },
                            onNext = { currentStep = SyncWizardStep.QUALITY }
                        )
                    }
                    SyncWizardStep.QUALITY -> {
                        QualityStep(
                            profiles = availableProfiles,
                            selectedProfile = selectedProfile,
                            onSelectProfile = { selectedProfile = it },
                            onNext = { currentStep = SyncWizardStep.REVIEW }
                        )
                    }
                    SyncWizardStep.REVIEW -> {
                        ReviewStep(
                            plan = syncPlan,
                            totalStorageBytes = totalStorageBytes,
                            systemAndOtherBytes = (totalStorageBytes - availableStorageBytes - currentLocalMusicBytes).coerceAtLeast(0L),
                            currentLocalMusicBytes = currentLocalMusicBytes,
                            onStartSync = { onStartSync(syncPlan) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepProgressHeader(currentStep: SyncWizardStep) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepChip(number = "1", title = "Selección", isActive = currentStep == SyncWizardStep.SELECTION, isDone = currentStep.ordinal > 0)
        StepChip(number = "2", title = "Calidad", isActive = currentStep == SyncWizardStep.QUALITY, isDone = currentStep.ordinal > 1)
        StepChip(number = "3", title = "Preflight", isActive = currentStep == SyncWizardStep.REVIEW, isDone = false)
    }
}

@Composable
private fun StepChip(number: String, title: String, isActive: Boolean, isDone: Boolean) {
    val bg = when {
        isActive -> AccentCoral.copy(alpha = 0.2f)
        isDone -> TertiaryCyan.copy(alpha = 0.15f)
        else -> GlassFillLow
    }
    val border = when {
        isActive -> AccentCoral
        isDone -> TertiaryCyan
        else -> GlassBorderLow
    }
    val textCol = when {
        isActive -> AccentCoral
        isDone -> TertiaryCyan
        else -> TextDim
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = number,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = textCol
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MichiTypography.metadata,
            color = if (isActive || isDone) PureWhite else TextDim,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SelectionStep(
    selectedScope: SyncScope,
    totalCandidates: Int,
    candidateTracks: List<ManifestTrack>,
    manifestPlaylists: List<ManifestPlaylist>,
    selectedAlbumIds: Set<String>,
    selectedArtistIds: Set<String>,
    selectedPlaylistIds: Set<String>,
    selectedTrackIds: Set<String>,
    totalSelectedCount: Int,
    totalSelectedBytes: Long,
    onSelectScope: (SyncScope) -> Unit,
    onToggleAlbum: (String) -> Unit,
    onToggleArtist: (String) -> Unit,
    onTogglePlaylist: (String) -> Unit,
    onToggleTrack: (String) -> Unit,
    onSelectAllAlbums: () -> Unit,
    onClearAllAlbums: () -> Unit,
    onSelectAllArtists: () -> Unit,
    onClearAllArtists: () -> Unit,
    onSelectAllPlaylists: () -> Unit,
    onClearAllPlaylists: () -> Unit,
    onSelectAllTracks: () -> Unit,
    onClearAllTracks: () -> Unit,
    onNext: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "¿Qué deseas sincronizar?",
            style = MichiTypography.cardTitle,
            color = PureWhite,
            fontSize = 20.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Elige el alcance de contenido para transferir desde el servidor a este móvil.",
            style = MichiTypography.metadata,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(20.dp))

        ScopeOptionCard(
            scope = SyncScope.FULL_LIBRARY,
            title = "Biblioteca Completa",
            subtitle = "$totalCandidates pistas disponibles en el catálogo remoto",
            isSelected = selectedScope == SyncScope.FULL_LIBRARY,
            onSelect = { onSelectScope(SyncScope.FULL_LIBRARY) },
            testTag = "sync_wizard_selection_full_library"
        )

        Spacer(modifier = Modifier.height(12.dp))

        ScopeOptionCard(
            scope = SyncScope.PLAYLISTS,
            title = "Playlists Remotas",
            subtitle = if (manifestPlaylists.isEmpty()) "Sin playlists en el servidor" else "${manifestPlaylists.size} playlist(s) disponibles",
            isSelected = selectedScope == SyncScope.PLAYLISTS,
            onSelect = { onSelectScope(SyncScope.PLAYLISTS) },
            testTag = "sync_wizard_selection_playlists"
        )

        Spacer(modifier = Modifier.height(12.dp))

        val distinctAlbums = remember(candidateTracks) {
            candidateTracks.groupBy { it.album.ifEmpty { "Desconocido" } }
        }

        ScopeOptionCard(
            scope = SyncScope.ALBUMS,
            title = "Álbumes Específicos",
            subtitle = "${distinctAlbums.size} álbumes en catálogo",
            isSelected = selectedScope == SyncScope.ALBUMS,
            onSelect = { onSelectScope(SyncScope.ALBUMS) },
            testTag = "sync_wizard_selection_albums"
        )

        Spacer(modifier = Modifier.height(12.dp))

        val distinctArtists = remember(candidateTracks) {
            candidateTracks.groupBy { it.artist.ifEmpty { "Desconocido" } }
        }

        ScopeOptionCard(
            scope = SyncScope.ARTISTS,
            title = "Artistas Específicos",
            subtitle = "${distinctArtists.size} artistas en catálogo",
            isSelected = selectedScope == SyncScope.ARTISTS,
            onSelect = { onSelectScope(SyncScope.ARTISTS) },
            testTag = "sync_wizard_selection_artists"
        )

        Spacer(modifier = Modifier.height(12.dp))

        ScopeOptionCard(
            scope = SyncScope.TRACKS,
            title = "Pistas Individuales",
            subtitle = "Selecciona pistas canción por canción",
            isSelected = selectedScope == SyncScope.TRACKS,
            onSelect = { onSelectScope(SyncScope.TRACKS) },
            testTag = "sync_wizard_selection_tracks"
        )

        // Sub-selector based on scope
        when (selectedScope) {
            SyncScope.FULL_LIBRARY -> {
                // All candidates included automatically
            }
            SyncScope.ALBUMS -> {
                Spacer(modifier = Modifier.height(20.dp))
                SubSelectionHeader(
                    title = "Álbumes disponibles (${selectedAlbumIds.size}/${distinctAlbums.size})",
                    onSelectAll = onSelectAllAlbums,
                    onClearAll = onClearAllAlbums,
                )
                Spacer(modifier = Modifier.height(8.dp))
                distinctAlbums.forEach { (albumName, tracksInAlbum) ->
                    val isChecked = albumName in selectedAlbumIds
                    val albumBytes = tracksInAlbum.sumOf { it.size }
                    ItemCheckboxRow(
                        title = albumName,
                        subtitle = "${tracksInAlbum.firstOrNull()?.artist ?: "Varios"} • ${tracksInAlbum.size} pistas • ${StoragePreflightEvaluator.formatBytes(albumBytes)}",
                        isChecked = isChecked,
                        onToggle = { onToggleAlbum(albumName) },
                        testTag = "sync_wizard_album_${albumName.replace(" ", "_")}"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
            SyncScope.PLAYLISTS -> {
                Spacer(modifier = Modifier.height(20.dp))
                SubSelectionHeader(
                    title = "Playlists disponibles (${selectedPlaylistIds.size}/${manifestPlaylists.size})",
                    onSelectAll = onSelectAllPlaylists,
                    onClearAll = onClearAllPlaylists,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (manifestPlaylists.isEmpty()) {
                    Text(
                        text = "El servidor no tiene playlists guardadas",
                        style = MichiTypography.metadata,
                        color = TextDim,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    manifestPlaylists.forEach { pl ->
                        val isChecked = pl.playlistId in selectedPlaylistIds
                        ItemCheckboxRow(
                            title = pl.name,
                            subtitle = "${pl.trackIds.size} pistas",
                            isChecked = isChecked,
                            onToggle = { onTogglePlaylist(pl.playlistId) },
                            testTag = "sync_wizard_playlist_${pl.playlistId}"
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
            SyncScope.ARTISTS -> {
                Spacer(modifier = Modifier.height(20.dp))
                SubSelectionHeader(
                    title = "Artistas disponibles (${selectedArtistIds.size}/${distinctArtists.size})",
                    onSelectAll = onSelectAllArtists,
                    onClearAll = onClearAllArtists,
                )
                Spacer(modifier = Modifier.height(8.dp))
                distinctArtists.forEach { (artistName, tracksOfArtist) ->
                    val isChecked = artistName in selectedArtistIds
                    ItemCheckboxRow(
                        title = artistName,
                        subtitle = "${tracksOfArtist.size} pistas",
                        isChecked = isChecked,
                        onToggle = { onToggleArtist(artistName) },
                        testTag = "sync_wizard_artist_${artistName.replace(" ", "_")}"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
            SyncScope.TRACKS -> {
                Spacer(modifier = Modifier.height(20.dp))
                SubSelectionHeader(
                    title = "Pistas disponibles (${selectedTrackIds.size}/${candidateTracks.size})",
                    onSelectAll = onSelectAllTracks,
                    onClearAll = onClearAllTracks,
                )
                Spacer(modifier = Modifier.height(8.dp))
                candidateTracks.forEach { trk ->
                    val isChecked = trk.trackId in selectedTrackIds
                    ItemCheckboxRow(
                        title = trk.title,
                        subtitle = "${trk.artist} • ${trk.album} • ${StoragePreflightEvaluator.formatBytes(trk.size)}",
                        isChecked = isChecked,
                        onToggle = { onToggleTrack(trk.trackId) },
                        testTag = "sync_wizard_track_${trk.trackId}"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Selected summary banner
        Card(
            modifier = Modifier.fillMaxWidth().clip(MichiShapes.sm),
            colors = CardDefaults.cardColors(containerColor = GlassFillHigh)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Selección actual:",
                    style = MichiTypography.metadata,
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    text = if (totalSelectedCount > 0) {
                        "$totalSelectedCount pista(s) (${StoragePreflightEvaluator.formatBytes(totalSelectedBytes)})"
                    } else {
                        "0 pistas"
                    },
                    style = MichiTypography.cardTitle,
                    color = if (totalSelectedCount > 0) TertiaryCyan else ErrorColor,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (totalSelectedCount > 0) {
            Button(
                onClick = onNext,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCoral, contentColor = PureWhite),
                shape = MichiShapes.md,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("sync_wizard_next_button")
            ) {
                Text("Siguiente: Calidad y Perfil", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        } else {
            Button(
                onClick = {},
                enabled = false,
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = Color(0xFF2A2C33),
                    disabledContentColor = TextDim
                ),
                shape = MichiShapes.md,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("sync_wizard_next_button")
            ) {
                Text("Selecciona al menos 1 pista para continuar", fontWeight = FontWeight.Normal, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SubSelectionHeader(
    title: String,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MichiTypography.sectionTitle, color = PureWhite, fontSize = 15.sp)
        Row {
            Text(
                text = "Todo",
                style = MichiTypography.metadata,
                color = AccentCoral,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable(onClick = onSelectAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("sync_wizard_select_all")
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Ninguno",
                style = MichiTypography.metadata,
                color = TextSecondary,
                modifier = Modifier
                    .clickable(onClick = onClearAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("sync_wizard_clear_all")
            )
        }
    }
}

@Composable
private fun ItemCheckboxRow(
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onToggle: () -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MichiShapes.sm)
            .background(if (isChecked) GlassFillHigh else GlassFillLow)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MichiTypography.trackTitle,
                color = PureWhite,
                fontSize = 14.sp,
                fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, style = MichiTypography.metadata, color = TextSecondary, fontSize = 12.sp)
        }
        Checkbox(
            checked = isChecked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = AccentCoral,
                uncheckedColor = GlassBorderHigh,
                checkmarkColor = PureWhite
            )
        )
    }
}

@Composable
private fun ScopeOptionCard(
    scope: SyncScope,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    testTag: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MichiShapes.md)
            .clickable(onClick = onSelect)
            .border(
                1.dp,
                if (isSelected) AccentCoral else GlassBorderLow,
                MichiShapes.md
            )
            .testTag(testTag),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) GlassFillHigh else GlassFillLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MichiTypography.cardTitle, color = PureWhite, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = subtitle, style = MichiTypography.metadata, color = TextSecondary, fontSize = 13.sp)
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Seleccionado",
                    tint = AccentCoral,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun QualityStep(
    profiles: List<ProfileAvailability>,
    selectedProfile: DeliveryProfile,
    onSelectProfile: (DeliveryProfile) -> Unit,
    onNext: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Perfil de Entrega y Calidad",
            style = MichiTypography.cardTitle,
            color = PureWhite,
            fontSize = 20.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Determina el formato de entrega. Las opciones de transcodificación requieren soporte explícito anunciado por el servidor.",
            style = MichiTypography.metadata,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(20.dp))

        profiles.forEach { item ->
            ProfileOptionCard(
                item = item,
                isSelected = selectedProfile == item.profile,
                onSelect = { if (item.isAvailable) onSelectProfile(item.profile) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onNext,
            colors = ButtonDefaults.buttonColors(containerColor = AccentCoral, contentColor = PureWhite),
            shape = MichiShapes.md,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("sync_wizard_quality_next_button")
        ) {
            Text("Siguiente: Revisar y Preflight", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ProfileOptionCard(
    item: ProfileAvailability,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val enabled = item.isAvailable
    val borderColor = when {
        isSelected && enabled -> AccentCoral
        !enabled -> GlassBorderLow.copy(alpha = 0.5f)
        else -> GlassBorderLow
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MichiShapes.md)
            .clickable(enabled = enabled, onClick = onSelect)
            .border(1.dp, borderColor, MichiShapes.md)
            .testTag("sync_wizard_profile_${item.profile.name}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected && enabled) GlassFillHigh else GlassFillLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.profile.label,
                        style = MichiTypography.cardTitle,
                        color = if (enabled) PureWhite else TextDim,
                        fontSize = 16.sp,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.codecDescription,
                        style = MichiTypography.metadata,
                        color = if (enabled) TertiaryCyan else TextDim,
                        fontSize = 12.sp,
                    )
                }

                if (!enabled) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(GlassFillLow)
                            .border(1.dp, GlassBorderLow, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("sync_wizard_profile_disabled_badge")
                    ) {
                        Text(
                            text = "No disponible",
                            style = MichiTypography.metadata,
                            color = TextDim,
                            fontSize = 11.sp,
                        )
                    }
                } else if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Seleccionado",
                        tint = AccentCoral,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.reasonIfNot ?: item.profile.description,
                style = MichiTypography.metadata,
                color = if (enabled) TextSecondary else TextDim,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ReviewStep(
    plan: SyncPlan,
    totalStorageBytes: Long,
    systemAndOtherBytes: Long,
    currentLocalMusicBytes: Long,
    onStartSync: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Revisión y Preflight de Almacenamiento",
            style = MichiTypography.cardTitle,
            color = PureWhite,
            fontSize = 20.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Evaluación determinista de espacio en disco, reserva de seguridad de 100 MB y deduplicación.",
            style = MichiTypography.metadata,
            color = TextSecondary,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Visual Storage Breakdown Bar
        StorageBreakdownBar(
            totalStorageBytes = totalStorageBytes,
            systemAndOtherBytes = systemAndOtherBytes,
            currentLocalMusicBytes = currentLocalMusicBytes,
            newSyncBytes = plan.totalDownloadBytes,
            safetyBufferBytes = plan.safetyBufferBytes,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Plan metrics breakdown
        PlanMetricsCard(plan = plan)

        Spacer(modifier = Modifier.height(16.dp))

        // Track-level decision inspection list
        TrackDecisionListCard(items = plan.items)

        Spacer(modifier = Modifier.height(16.dp))

        // Issues display
        plan.issues.forEach { issue ->
            IssueBanner(issue = issue)
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (plan.isExecutable) {
            Button(
                onClick = onStartSync,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCoral, contentColor = PureWhite),
                shape = MichiShapes.md,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sync_wizard_start_button")
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Iniciar Sincronización", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        } else {
            Button(
                onClick = {},
                enabled = false,
                colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFF2A2C33), disabledContentColor = TextDim),
                shape = MichiShapes.md,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sync_wizard_start_button_disabled")
            ) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = ErrorColor)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sincronización Bloqueada", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun PlanMetricsCard(plan: SyncPlan) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MichiShapes.md)
            .border(1.dp, GlassBorderLow, MichiShapes.md),
        colors = CardDefaults.cardColors(containerColor = GlassFillLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            MetricRow(label = "Pistas seleccionadas:", value = "${plan.totalSelectedTracks}")
            MetricRow(label = "Ya disponibles localmente (omitidas):", value = "${plan.tracksSkippedCount}")
            if (plan.tracksToResumeCount > 0) {
                MetricRow(label = "Reanudables (.part parciales):", value = "${plan.tracksToResumeCount}")
            }
            MetricRow(label = "Pistas a descargar:", value = "${plan.tracksToDownloadCount}")
            MetricRow(label = "Carga neta estimada:", value = StoragePreflightEvaluator.formatBytes(plan.totalDownloadBytes), isHighlight = true)
            MetricRow(label = "Espacio libre tras sincronización:", value = StoragePreflightEvaluator.formatBytes(plan.remainingStorageAfterSyncBytes))
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, isHighlight: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MichiTypography.metadata, color = TextSecondary, fontSize = 13.sp)
        Text(
            text = value,
            style = MichiTypography.metadata,
            color = if (isHighlight) AccentCoral else PureWhite,
            fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun IssueBanner(issue: org.michimusic.core.models.SyncPreflightIssue) {
    val isBlocking = issue.severity == IssueSeverity.BLOCKING
    val bg = if (isBlocking) Color(0x33FF5252) else Color(0x33FFB74D)
    val border = if (isBlocking) ErrorColor else Color(0xFFFFB74D)
    val icon = if (isBlocking) Icons.Filled.Error else Icons.Filled.Warning
    val tag = if (isBlocking) "sync_wizard_issue_blocking" else "sync_wizard_issue_warning"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = border,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = issue.message,
            style = MichiTypography.metadata,
            color = PureWhite,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TrackDecisionListCard(items: List<org.michimusic.core.models.SyncItemDecision>) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MichiShapes.md)
            .border(1.dp, GlassBorderLow, MichiShapes.md)
            .testTag("sync_wizard_track_decision_card"),
        colors = CardDefaults.cardColors(containerColor = GlassFillLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Detalle por pista (${items.size})",
                    style = MichiTypography.cardTitle,
                    color = PureWhite,
                    fontSize = 14.sp
                )
                Text(
                    text = if (expanded) "Ocultar" else "Ver lista",
                    style = MichiTypography.metadata,
                    color = AccentCoral,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }

            if (expanded) {
                var searchQuery by remember { mutableStateOf("") }
                var showAll by remember { mutableStateOf(false) }

                Spacer(modifier = Modifier.height(10.dp))

                if (items.size > 10) {
                    androidx.compose.material3.OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Buscar pista o artista...", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCoral,
                            unfocusedBorderColor = GlassBorderLow,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val filtered = remember(items, searchQuery) {
                    if (searchQuery.isBlank()) items else items.filter {
                        it.title.contains(searchQuery, ignoreCase = true) ||
                        it.artist.contains(searchQuery, ignoreCase = true)
                    }
                }

                var visibleCount by remember { mutableStateOf(50) }

                val displayedItems = remember(filtered, visibleCount) {
                    filtered.take(visibleCount)
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = displayedItems,
                        key = { it.trackId }
                    ) { item ->
                        TrackDecisionRow(item = item)
                    }
                }

                if (visibleCount < filtered.size) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Mostrando $visibleCount de ${filtered.size} pistas. Cargar 50 más...",
                        style = MichiTypography.metadata,
                        color = AccentCoral,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(MichiShapes.xs)
                            .clickable { visibleCount = minOf(visibleCount + 50, filtered.size) }
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackDecisionRow(item: org.michimusic.core.models.SyncItemDecision) {
    val badgeColor = when (item.action) {
        SyncItemAction.DOWNLOAD -> AccentCoral
        SyncItemAction.SKIP_ALREADY_EXISTS -> TertiaryCyan
        SyncItemAction.RESUME_PARTIAL -> Color(0xFFFFB74D)
        SyncItemAction.REPLACE_BETTER_QUALITY -> Color(0xFF81C784)
        SyncItemAction.BLOCKED_UNSUPPORTED -> ErrorColor
    }
    val badgeText = when (item.action) {
        SyncItemAction.DOWNLOAD -> "Descargar"
        SyncItemAction.SKIP_ALREADY_EXISTS -> "Existe"
        SyncItemAction.RESUME_PARTIAL -> "Reanudar"
        SyncItemAction.REPLACE_BETTER_QUALITY -> "Mejor calidad"
        SyncItemAction.BLOCKED_UNSUPPORTED -> "Bloqueada"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MichiTypography.metadata,
                color = PureWhite,
                maxLines = 1,
                fontSize = 12.sp
            )
            Text(
                text = item.artist,
                style = MichiTypography.metadata,
                color = TextSecondary,
                maxLines = 1,
                fontSize = 11.sp
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(badgeColor.copy(alpha = 0.2f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = badgeText,
                style = MichiTypography.metadata,
                color = badgeColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
        }
    }
}

