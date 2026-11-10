package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import org.michimusic.mobile.screens.PlaylistsViewModel
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.CreatePlaylistDialog
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
    var showCreateDialog by remember { mutableStateOf(false) }
    val appContext = LocalContext.current.applicationContext
    val visualStore = remember(appContext) { PlaylistVisualStore(appContext) }
    val visualRevision by PlaylistVisualStore.revision.collectAsStateWithLifecycle()
    var visualById by remember { mutableStateOf<Map<String, PlaylistVisualState>>(emptyMap()) }
    var catalogSort by remember { mutableStateOf(PlaylistCatalogSort.NAME) }
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

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "COLECCIONES",
                        style = MichiTypography.screenEyebrow,
                    )
                    Text(
                        text = "Tus Playlists",
                        style = MichiTypography.screenTitle,
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

            if (playlists.isNotEmpty() && !isLoading) {
                val pinnedCount = orderedPlaylists.count { visualById[it.id]?.pinned == true }
                Text(
                    text = "${orderedPlaylists.size} ${if (orderedPlaylists.size == 1) "playlist" else "playlists"}" +
                        if (pinnedCount > 0) " · $pinnedCount ${if (pinnedCount == 1) "fijada" else "fijadas"}" else "",
                    style = MichiTypography.microLabel,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp).testTag("playlist_catalog_summary"),
                )
                Spacer(Modifier.height(7.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("playlist_catalog_sort"),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    AeroFilterChip(
                        label = "Nombre", selected = catalogSort == PlaylistCatalogSort.NAME,
                        onClick = {
                            catalogSort = PlaylistCatalogSort.NAME
                            visualScope.launch { visualStore.setCatalogSort(catalogSort) }
                        }, testTag = "playlist_sort_name",
                    )
                    AeroFilterChip(
                        label = "Más canciones", selected = catalogSort == PlaylistCatalogSort.TRACKS,
                        onClick = {
                            catalogSort = PlaylistCatalogSort.TRACKS
                            visualScope.launch { visualStore.setCatalogSort(catalogSort) }
                        }, testTag = "playlist_sort_tracks",
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

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
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(orderedPlaylists, key = { it.id }) { playlist ->
                        AeroCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("playlist_item_${playlist.id}"),
                            finish = if (visualById[playlist.id]?.pinned == true) AeroFinish.Satin else AeroFinish.Plain,
                            onClick = { onNavigateToPlaylist(playlist.id) },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                val isPinned = visualById[playlist.id]?.pinned == true
                                Box(
                                    Modifier
                                        .width(3.dp)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(if (isPinned) PrimaryPinkContainer else Color.Transparent),
                                )
                                if (visualById[playlist.id]?.coverUri != null) {
                                    AlbumArtView(
                                        coverStyle = org.michimusic.mobile.ui.components.coverStyleFor(playlist.name),
                                        imageModel = visualById[playlist.id]?.coverUri,
                                        modifier = Modifier.size(52.dp), cornerRadius = 12.dp,
                                    )
                                } else {
                                    org.michimusic.mobile.ui.components.PlaylistCoverMosaic(
                                        coverIds = playlist.coverIds,
                                        coverStyle = org.michimusic.mobile.ui.components.coverStyleFor(playlist.name),
                                        modifier = Modifier.size(52.dp),
                                        size = 52.dp,
                                        cornerRadius = 12.dp,
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PureWhite,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = if (playlist.trackCount == 1) "1 canción" else "${playlist.trackCount} canciones",
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                    )
                                    if (visualById[playlist.id]?.pinned == true) {
                                        Text("Fijada", color = PrimaryPinkContainer,
                                            style = MichiTypography.microLabel,
                                            modifier = Modifier.testTag("playlist_pinned_${playlist.id}"))
                                    }
                                }

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
                                    iconSize = 24.dp,
                                    minTargetSize = MichiSpacing.minTouchTarget,
                                    testTag = "playlist_play_${playlist.id}",
                                )
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
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
