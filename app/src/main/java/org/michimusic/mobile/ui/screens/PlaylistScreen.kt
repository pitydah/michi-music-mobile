package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import org.michimusic.mobile.ui.components.AeroPremiumBackdrop
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import org.michimusic.mobile.ui.components.AeroActionButton
import org.michimusic.mobile.ui.components.AeroActionKind
import org.michimusic.mobile.ui.components.AeroFilterChip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.michimusic.mobile.screens.PlaylistItem
import org.michimusic.mobile.screens.PlaylistsViewModel
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.CreatePlaylistDialog
import org.michimusic.mobile.ui.components.DeletePlaylistDialog
import org.michimusic.mobile.ui.components.RenamePlaylistDialog
import org.michimusic.mobile.ui.components.AeroCard
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistScreen(
    onNavigateToPlaylist: (String) -> Unit = {},
    viewModel: PlaylistsViewModel = koinViewModel(),
    audioController: org.michimusic.player.AudioController = org.koin.compose.koinInject(),
    sessionManager: org.michimusic.mobile.playback.PlaybackSessionManager? = null,
    historyDao: org.michimusic.data.cache.AppDao = org.koin.compose.koinInject(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isCreatingPlaylist by viewModel.isCreatingPlaylist.collectAsStateWithLifecycle()
    val createPlaylistError by viewModel.createPlaylistError.collectAsStateWithLifecycle()
    val isRenamingPlaylist by viewModel.isRenamingPlaylist.collectAsStateWithLifecycle()
    val isDeletingPlaylist by viewModel.isDeletingPlaylist.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var menuPlaylist by remember { mutableStateOf<PlaylistItem?>(null) }
    var renameTarget by remember { mutableStateOf<PlaylistItem?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistItem?>(null) }
    var appearanceTarget by remember { mutableStateOf<PlaylistItem?>(null) }
    var isSavingVisual by remember { mutableStateOf(false) }
    var visualEditError by remember { mutableStateOf<String?>(null) }
    val appContext = LocalContext.current.applicationContext
    val visualStore = remember(appContext) { PlaylistVisualStore(appContext) }
    val visualRevision by PlaylistVisualStore.revision.collectAsStateWithLifecycle()
    var visualById by remember { mutableStateOf<Map<String, PlaylistVisualState>>(emptyMap()) }
    var catalogSort by remember { mutableStateOf(PlaylistCatalogSort.NAME) }
    // One visual preference only: classic stays the default, deck is opt-in.
    var cardsMode by rememberSaveable { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val visualScope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.loadPlaylists() }
    LaunchedEffect(Unit) {
        catalogSort = visualStore.readCatalogSort()
        visualStore.pruneOrphanPrivateImages()
    }
    LaunchedEffect(playlists, visualRevision) {
        visualById = visualStore.readMany(playlists.map { it.id })
    }
    val indexed = remember(playlists) { playlists.associateBy { it.id } }
    val orderedPlaylists = remember(playlists, visualById, catalogSort) {
        orderedPlaylistCatalog(
            entries = playlists.map { PlaylistCatalogEntry(it.id, it.name, it.trackCount) },
            pinnedIds = visualById.filterValues { it.pinned }.keys,
            sort = catalogSort,
        ).mapNotNull { indexed[it.id] }
    }

    val launchHistoryStore = remember(appContext) { PlaylistLaunchHistoryStore(appContext) }

    val playlistActions: @Composable RowScope.(PlaylistItem) -> Unit = { playlist ->
        val isPinned = visualById[playlist.id]?.pinned == true
        MichiIconAction(
            icon = Icons.Default.PlayArrow,
            contentDescription = "Reproducir ${playlist.name}",
            onClick = {
                if (sessionManager != null) {
                    viewModel.playPlaylist(
                        playlist.id, audioController = audioController,
                        sessionManager = sessionManager,
                        onResult = { result ->
                            if (result is org.michimusic.mobile.screens.PlayPlaylistResult.Success) {
                                visualScope.launch {
                                    // Privacy is authoritative. A queue acceptance is
                                    // evidence of a command, NOT that a song was heard.
                                    val allowed = runCatching {
                                        historyDao.getSetting("listening_history_enabled") != "false"
                                    }.getOrDefault(false)
                                    if (!allowed) launchHistoryStore.clear()
                                    else launchHistoryStore.recordAccepted(playlist.id, result.trackCount)
                                }
                            }
                        },
                    )
                } else {
                    viewModel.playPlaylist(playlist.id, audioController = audioController)
                }
            },
            tint = PureWhite,
            iconSize = 22.dp,
            minTargetSize = MichiSpacing.minTouchTarget,
            testTag = "playlist_play_${playlist.id}",
        )
        Box {
            MichiIconAction(
                icon = Icons.Default.MoreVert,
                contentDescription = "Más opciones de ${playlist.name}",
                onClick = { menuPlaylist = playlist },
                tint = TextSecondary,
                iconSize = 20.dp,
                minTargetSize = MichiSpacing.minTouchTarget,
                testTag = "playlist_more_${playlist.id}",
            )
            DropdownMenu(
                expanded = menuPlaylist?.id == playlist.id,
                onDismissRequest = { menuPlaylist = null },
            ) {
                DropdownMenuItem(
                    text = { Text("Renombrar") },
                    onClick = { menuPlaylist = null; renameTarget = playlist },
                    modifier = Modifier.testTag("playlist_menu_rename_${playlist.id}"),
                )
                DropdownMenuItem(
                    text = { Text(if (isPinned) "Desfijar" else "Fijar") },
                    onClick = {
                        menuPlaylist = null
                        visualScope.launch { visualStore.setPinned(playlist.id, !isPinned) }
                    },
                    modifier = Modifier.testTag("playlist_menu_pin_${playlist.id}"),
                )
                DropdownMenuItem(
                    text = { Text("Editar apariencia") },
                    onClick = {
                        menuPlaylist = null
                        visualEditError = null
                        appearanceTarget = playlist
                    },
                    modifier = Modifier.testTag("playlist_menu_appearance_${playlist.id}"),
                )
                DropdownMenuItem(
                    text = { Text("Gestionar canciones") },
                    onClick = { menuPlaylist = null; onNavigateToPlaylist(playlist.id) },
                    modifier = Modifier.testTag("playlist_menu_manage_${playlist.id}"),
                )
                DropdownMenuItem(
                    text = { Text("Eliminar playlist") },
                    onClick = { menuPlaylist = null; deleteTarget = playlist },
                    modifier = Modifier.testTag("playlist_menu_delete_${playlist.id}"),
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
    ) {
        // Fifth primary destination: same top-chrome surface as the other tabs.
        // The background now reaches physical y=0 while content keeps the inset.
        AeroPremiumBackdrop(accent = PrimaryPinkContainer)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = MichiSpacing.screenHorizontal),
        ) {
            Spacer(Modifier.height(16.dp))

            // UI8-KILLCRITIC (post-merge): the mode selector belongs to the header, next to
            // the create button — never on a full-width row of its own. FlowRow keeps the
            // pair together: they sit to the right of the title when they fit, and the whole
            // group drops to a second line when they do not. The title is never truncated and
            // the selector is never separated from "+".
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "COLECCIONES",
                        style = MichiTypography.screenEyebrow,
                    )
                    Text(
                        text = "Tus Playlists",
                        style = MichiTypography.screenTitle.copy(
                            fontSize = 28.sp,
                            lineHeight = 34.sp,
                        ),
                    )
                }

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(
                        10.dp,
                        Alignment.End,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (playlists.isNotEmpty() && !isLoading) {
                        PlaylistViewModeToggle(
                            cardsMode = cardsMode,
                            onModeChange = { cards ->
                                cardsMode = cards
                                menuPlaylist = null
                            },
                        )
                    }
                    // Add Playlist Button
                    MichiIconAction(
                        icon = Icons.Default.Add,
                        contentDescription = "Nueva Playlist",
                        onClick = { showCreateDialog = true },
                        tint = PureWhite,
                        iconSize = 24.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                    )
                }
            }

            if (playlists.isNotEmpty() && !isLoading) {
                val pinnedCount = orderedPlaylists.count { visualById[it.id]?.pinned == true }
                val summary = playlistCatalogSummary(orderedPlaylists.size, pinnedCount)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = summary,
                        style = MichiTypography.metadata,
                        color = TextSecondary,
                        modifier = Modifier.testTag("playlist_catalog_summary"),
                    )
                    Box(modifier = Modifier.testTag("playlist_catalog_sort")) {
                        AeroFilterChip(
                            label = (if (catalogSort == PlaylistCatalogSort.NAME) "Nombre" else "Más canciones") + "  ▾",
                            selected = false,
                            onClick = { showSortMenu = true },
                            testTag = "playlist_sort_trigger",
                        )
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Nombre") },
                                onClick = {
                                    showSortMenu = false
                                    catalogSort = PlaylistCatalogSort.NAME
                                    visualScope.launch { visualStore.setCatalogSort(catalogSort) }
                                },
                                modifier = Modifier.testTag("playlist_sort_name"),
                            )
                            DropdownMenuItem(
                                text = { Text("Más canciones") },
                                onClick = {
                                    showSortMenu = false
                                    catalogSort = PlaylistCatalogSort.TRACKS
                                    visualScope.launch { visualStore.setCatalogSort(catalogSort) }
                                },
                                modifier = Modifier.testTag("playlist_sort_tracks"),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Cargando playlists...", color = TextSecondary)
                }
            } else if (playlists.isEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    AeroCard(
                        modifier = Modifier.fillMaxWidth().testTag("playlist_empty_card"),
                        finish = AeroFinish.Satin,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = PrimaryPinkContainer,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = "Crea tu primera playlist",
                                style = MichiTypography.cardTitle,
                                color = PureWhite,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Agrupa canciones de tu biblioteca. También puedes crear listas desde cero y añadir música después.",
                                style = MichiTypography.metadata,
                                color = TextSecondary,
                            )
                            AeroActionButton(
                                label = "Crear playlist",
                                onClick = { showCreateDialog = true },
                                icon = Icons.Default.Add,
                                kind = AeroActionKind.Primary,
                                modifier = Modifier.fillMaxWidth(),
                                testTag = "playlist_empty_create",
                            )
                        }
                    }
                }
            } else if (cardsMode) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .testTag("playlist_catalog_cards"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (orderedPlaylists.size == 1)
                            "Una playlist · toca para abrirla"
                        else "Desliza las tarjetas para explorar tus playlists",
                        style = MichiTypography.metadata,
                        color = TextSecondary,
                    )
                    PlaylistSwipingDeck(
                        cards = orderedPlaylists,
                        key = { it.id },
                        // UI8-KILLCRITIC: no fixed height and no horizontal inset of its
                        // own. The front card owns the geometry, so the deck is exactly as
                        // wide and as tall as the Lista hero. The bottom space is drawing
                        // room for the stacked cards, not part of the card.
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 26.dp),
                        maxVisibleCards = minOf(3, orderedPlaylists.size),
                        isSwipeEnabled = orderedPlaylists.size > 1 && menuPlaylist == null,
                        // Swipe cycles; it never plays, deletes or changes the data order.
                    ) { playlist ->
                        PlaylistCatalogSpotlight(
                            playlist = playlist,
                            visual = visualById[playlist.id] ?: PlaylistVisualState(),
                            onOpen = { onNavigateToPlaylist(playlist.id) },
                            actions = { playlistActions(playlist) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = "Toca una tarjeta para abrirla. Usa Lista para ver todas en lista.",
                        style = MichiTypography.metadata,
                        color = TextSecondary,
                    )
                }
            } else {
                // UI8-KILLCRITIC (post-merge): Lista is the same card as the front of the
                // deck, repeated for every playlist. A first hero plus compact rows made the
                // same playlist change shape just by being second, so position no longer
                // selects a different design: one measured component, one geometry.
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("playlist_catalog_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = orderedPlaylists,
                        key = { it.id },
                        contentType = { "playlist_spotlight" },
                    ) { playlist ->
                        PlaylistCatalogSpotlight(
                            playlist = playlist,
                            visual = visualById[playlist.id] ?: PlaylistVisualState(),
                            onOpen = { onNavigateToPlaylist(playlist.id) },
                            actions = { playlistActions(playlist) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item(key = "catalog_bottom") {
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }

        renameTarget?.let { target ->
            RenamePlaylistDialog(
                currentName = target.name,
                isSaving = isRenamingPlaylist,
                onConfirm = { newName ->
                    viewModel.renamePlaylist(target.id, newName) { success ->
                        if (success) renameTarget = null
                    }
                },
                onDismiss = { if (!isRenamingPlaylist) renameTarget = null },
            )
        }

        deleteTarget?.let { target ->
            DeletePlaylistDialog(
                isDeleting = isDeletingPlaylist,
                onConfirm = {
                    viewModel.deletePlaylist(target.id) { success ->
                        if (success) deleteTarget = null
                    }
                },
                onDismiss = { if (!isDeletingPlaylist) deleteTarget = null },
            )
        }

        appearanceTarget?.let { target ->
            // Freeze the draft baseline when the editor opens. A catalog revision
            // from another screen must not silently re-base this dialog: the store
            // compares expectedVisual and returns Conflict instead of overwriting.
            val baseline = remember(target) {
                visualById[target.id] ?: PlaylistVisualState()
            }
            PlaylistVisualEditorDialog(
                visual = baseline,
                isSaving = isSavingVisual,
                errorMessage = visualEditError,
                onDismiss = {
                    if (!isSavingVisual) {
                        appearanceTarget = null
                        visualEditError = null
                    }
                },
                onConfirm = { request ->
                    if (!isSavingVisual) {
                        isSavingVisual = true
                        visualEditError = null
                        visualScope.launch {
                            when (val result = visualStore.applyVisualEdit(target.id, request)) {
                                PlaylistVisualEditResult.Success -> {
                                    visualById = visualById + (target.id to visualStore.read(target.id))
                                    appearanceTarget = null
                                    isSavingVisual = false
                                }
                                PlaylistVisualEditResult.Conflict -> {
                                    visualEditError = "La apariencia cambió en otra pantalla. " +
                                        "Cierra y vuelve a abrir el editor para revisar los cambios."
                                    isSavingVisual = false
                                }
                                is PlaylistVisualEditResult.Rejected -> {
                                    val label = if (result.field == "banner") "Banner" else "Portada"
                                    visualEditError = "$label: " +
                                        playlistImageImportErrorMessage(result.reason)
                                    isSavingVisual = false
                                }
                            }
                        }
                    }
                },
            )
        }

        if (showCreateDialog) {
            CreatePlaylistDialog(
                isCreating = isCreatingPlaylist,
                errorMessage = createPlaylistError,
                onCreate = { name ->
                    viewModel.createPlaylist(name) { result ->
                        if (result is org.michimusic.mobile.screens.CreatePlaylistResult.Success) {
                            showCreateDialog = false
                        }
                    }
                },
                onDismiss = { showCreateDialog = false },
            )
        }
    }
}

/**
 * UI8-KILLCRITIC: one icon for the two-mode switch, matching `ArtistViewModeToggle`.
 *
 * The segmented capsule was rejected after two rounds of measurement: even at 118 dp it
 * read as a second control competing with the title. A single icon carries the same
 * two modes with no surface of its own.
 */
@Composable
private fun PlaylistViewModeToggle(
    cardsMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // UI8-KILLCRITIC: Playlists has two modes, so it gets the same single-icon switch
    // Artists already uses instead of a segmented capsule. The icon announces the mode
    // the button will switch to, and the transparent 48 dp target keeps the header light.
    MichiIconAction(
        icon = if (cardsMode) {
            Icons.AutoMirrored.Filled.ViewList
        } else {
            Icons.Default.Style
        },
        contentDescription = if (cardsMode) {
            "Vista tarjetas de playlists. Cambiar a lista"
        } else {
            "Vista lista de playlists. Cambiar a tarjetas"
        },
        onClick = { onModeChange(!cardsMode) },
        modifier = modifier,
        tint = if (cardsMode) PrimaryPinkContainer else PureWhite,
        iconSize = 22.dp,
        minTargetSize = MichiSpacing.minTouchTarget,
        testTag = "playlist_view_mode_toggle",
    )
}

