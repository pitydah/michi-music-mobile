package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Headphones
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.michimusic.data.repository.FavoritesRepository
import org.michimusic.mobile.ui.components.AddToPlaylistDialog
import org.michimusic.mobile.ui.components.TrackActionBottomSheet
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.michimusic.mobile.playback.EndpointType
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.playback.SignalTruthResolver
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.EqualizerDialog
import org.michimusic.mobile.ui.components.GradientProgressBar
import org.michimusic.mobile.ui.components.shouldScrollNowPlaying
import org.michimusic.mobile.ui.components.scrollArtworkHeightDp
import org.michimusic.mobile.ui.components.decodedPcmLabel
import org.michimusic.mobile.ui.components.QueueDialog
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatRemainingTimeSeconds
import org.michimusic.mobile.ui.components.formatTimeSeconds
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.aeroMaterialStyle
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.SurfaceContainerLowest
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.player.AudioController

private fun cycleSleepTimer(current: Long): Long = when {
    current <= 0L -> 15 * 60_000L
    current <= 15 * 60_000L -> 30 * 60_000L
    current <= 30 * 60_000L -> 60 * 60_000L
    else -> 0L
}

@Composable
fun NowPlayingScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sessionManager: PlaybackSessionManager = koinInject()
    val sessionState by sessionManager.sessionState.collectAsState()
    val audioController: AudioController = koinInject()
    val favoritesRepo: FavoritesRepository = koinInject()
    val coroutineScope = rememberCoroutineScope()
    val favoriteIds by favoritesRepo.observeFavoriteTrackIds().collectAsState(initial = emptySet())
    val playerState by audioController.state.collectAsState()

    val presentation = org.michimusic.mobile.playback.resolvePlaybackPresentation(sessionState, playerState)
    val currentTrack = presentation.currentTrack
    val activeEndpoint = presentation.endpoint
    val isPlaying = presentation.isPlaying
    val positionSeconds = (presentation.position / 1000f).coerceAtLeast(0f)
    val durationSeconds = (presentation.duration / 1000f).coerceAtLeast(0f)

    val isShuffle = presentation.shuffleMode == 1
    val repeatMode = presentation.repeatMode
    // This singleton is the same instance fed by the Media3 service.
    val signalTracker: org.michimusic.player.SignalTruthTracker = koinInject()
    val signalObservation by signalTracker.observation.collectAsState()

    var showQueueDialog by remember { mutableStateOf(false) }
    var showEqualizerDialog by remember { mutableStateOf(false) }
    var showAudioRouteDialog by remember { mutableStateOf(false) }
    var showSignalTruthDialog by remember { mutableStateOf(false) }
    var showTrackActionMenu by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val compactLayout = configuration.screenHeightDp < 730 || configuration.fontScale > 1.15f
    val scrollablePlayer = shouldScrollNowPlaying(configuration.screenHeightDp, configuration.fontScale)
    val raised = aeroMaterialStyle(AeroSurfaceRole.Raised, LocalVisualPreferences.current.highContrast)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
            .testTag("now_playing_screen"),
    ) {
        // Solid dark material with a quiet tonal gradient: no oversized painted halos.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(
                    listOf(SurfaceContainerLowest, SurfaceObsidian, SurfaceObsidian),
                )),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = if (compactLayout) 16.dp else 24.dp, vertical = 8.dp)
                .then(if (scrollablePlayer) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(bottom = if (scrollablePlayer) 24.dp else 0.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MichiIconAction(
                    icon = Icons.Filled.ExpandMore,
                    contentDescription = "Colapsar reproductor",
                    onClick = onBack,
                    tint = PureWhite,
                    iconSize = 28.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                    testTag = "now_playing_close_button",
                )

                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (currentTrack == null) "SIN REPRODUCCIÓN" else if (isPlaying) "REPRODUCIENDO" else "EN PAUSA",
                        style = MichiTypography.screenEyebrow,
                    )
                    Text(
                        text = currentTrack?.album ?: "Michi Music",
                        style = MichiTypography.metadata,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val sleepTimerRemaining = playerState.sleepTimerRemainingMs
                    val sleepActive = sleepTimerRemaining > 0L

                    MichiIconAction(
                        icon = Icons.Filled.Timer,
                        contentDescription = if (sleepActive) "Temporizador activo. Cambiar duración" else "Activar temporizador",
                        onClick = {
                            val next = cycleSleepTimer(sleepTimerRemaining)
                            if (next <= 0L) audioController.cancelSleepTimer() else audioController.setSleepTimer(next)
                        },
                        tint = if (sleepActive) TertiaryCyan else PureWhite,
                        iconSize = 22.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                        testTag = "sleep_timer_button",
                    )

                    MichiIconAction(
                        icon = Icons.Filled.MoreVert,
                        contentDescription = if (currentTrack != null) "Más opciones de la canción" else "Sin canción seleccionada",
                        onClick = { showTrackActionMenu = true },
                        enabled = currentTrack != null,
                        tint = if (currentTrack != null) PureWhite else OnSurfaceVariant,
                        iconSize = 24.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                        testTag = "now_playing_more_button",
                    )
                }
            }

            // Artwork respects available vertical space on compact / large-font screens.
            // A width-only 1:1 image could otherwise overlap transport controls.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (scrollablePlayer) Modifier.height(
                            scrollArtworkHeightDp(configuration.screenHeightDp).dp,
                        ) else Modifier.weight(1f)
                    )
                    .padding(vertical = if (compactLayout) 6.dp else 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                val artSide = minOf(maxWidth * 0.89f, maxHeight).coerceAtLeast(0.dp)
                Box(
                    modifier = Modifier
                        .size(artSide)
                        .shadow(elevation = 10.dp, shape = MichiShapes.lg)
                        .clip(MichiShapes.lg)
                        .border(raised.borderWidth, raised.outline, MichiShapes.lg)
                        .testTag("now_playing_album_art"),
                ) {
                    AlbumArtView(
                        coverStyle = coverStyleFor(currentTrack?.coverId?.ifEmpty { currentTrack.title } ?: currentTrack?.title),
                        imageModel = currentTrack?.coverId?.takeIf { it.isNotBlank() },
                        fallbackMediaUri = currentTrack?.filepath,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 24.dp,
                        borderWidth = 0.dp,
                    )
                }
            }

            // Track Info & Destination Capsule
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (compactLayout) 8.dp else 14.dp),
            ) {
                // Information hierarchy: title + artist row with direct favorite action
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = currentTrack?.title ?: "Sin reproducción activa",
                                style = if (compactLayout) MichiTypography.screenTitle.copy(fontSize = 23.sp, lineHeight = 28.sp)
                                    else MichiTypography.screenTitle,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = currentTrack?.artist ?: "Selecciona una canción",
                                style = MichiTypography.sectionTitle.copy(fontWeight = FontWeight.Normal),
                                color = OnSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (currentTrack != null) {
                            val isFav = favoriteIds.contains(currentTrack.id)
                            MichiIconAction(
                                icon = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (isFav) "Quitar de favoritos" else "Añadir a favoritos",
                                onClick = {
                                    coroutineScope.launch { favoritesRepo.toggleFavorite(currentTrack.id) }
                                },
                                tint = if (isFav) PrimaryPinkContainer else OnSurfaceVariant,
                                iconSize = 24.dp,
                                minTargetSize = MichiSpacing.minTouchTarget,
                                testTag = "now_playing_favorite_button",
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            val decoded = signalObservation.decodedFormat
                            val label = if (!sessionState.isRemoteActive && decoded != null) {
                                decodedPcmLabel(decoded.sampleRate, decoded.bitDepth) ?: "Signal Truth"
                            } else "Signal Truth"
                            Box(
                                modifier = Modifier
                                    .clip(MichiShapes.pill)
                                    .background(TertiaryCyan.copy(alpha = 0.10f))
                                    .border(0.65.dp, TertiaryCyan.copy(alpha = 0.24f), MichiShapes.pill)
                                    .clickable(role = Role.Button) { showSignalTruthDialog = true }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                    .testTag("signal_truth_button"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = label, color = TertiaryCyan,
                                    style = MichiTypography.metadata, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        item {
                            Row(
                                modifier = Modifier
                                    .clip(MichiShapes.pill)
                                    .background(raised.fill)
                                    .border(raised.borderWidth, raised.outline, MichiShapes.pill)
                                    .clickable(role = Role.Button) { showAudioRouteDialog = true }
                                    .heightIn(min = 48.dp)
                                    .widthIn(max = 232.dp)
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                    .testTag("audio_route_button"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Filled.Headphones, contentDescription = null,
                                    tint = if (activeEndpoint.isLocal) TertiaryCyan else PrimaryPinkContainer,
                                    modifier = Modifier.size(16.dp))
                                Text(text = presentation.endpointDescription, color = PureWhite,
                                    style = MichiTypography.metadata, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Filled.ExpandMore, contentDescription = null,
                                    tint = OnSurfaceVariant, modifier = Modifier.size(14.dp))
                            }
                        }
                    }

                    sessionState.statusMessage?.let { status ->
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MichiShapes.pill)
                                .background(if (sessionState.lastSessionError != null) PrimaryPinkContainer.copy(alpha = 0.15f) else TertiaryCyan.copy(alpha = 0.12f))
                                .border(
                                    0.75.dp,
                                    if (sessionState.lastSessionError != null) PrimaryPinkContainer.copy(alpha = 0.35f) else TertiaryCyan.copy(alpha = 0.30f),
                                    MichiShapes.pill
                                )
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                                .testTag("now_playing_status_banner"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = status,
                                style = MichiTypography.microLabel,
                                color = if (sessionState.lastSessionError != null) PrimaryPinkContainer else TertiaryCyan,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }

                // Progress Bar
                Column(modifier = Modifier.fillMaxWidth()) {
                    GradientProgressBar(
                        currentValue = positionSeconds,
                        maxValue = durationSeconds,
                        onSeek = { seekSec ->
                            sessionManager.seekTo((seekSec * 1000).toLong())
                        },
                        trackHeight = 5.dp,
                        showThumb = true,
                        testTag = "now_playing_seeker",
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (durationSeconds > 0f) formatTimeSeconds(positionSeconds) else "—:—",
                            style = MichiTypography.microLabel,
                            color = OnSurfaceVariant,
                        )
                        Text(
                            text = if (durationSeconds > 0f) formatRemainingTimeSeconds(positionSeconds, durationSeconds) else "—:—",
                            style = MichiTypography.microLabel,
                            color = OnSurfaceVariant,
                        )
                    }
                }

                // Main Playback Controls
                org.michimusic.mobile.ui.components.premium.MichiPlaybackControls(
                    state = org.michimusic.mobile.ui.components.premium.PlaybackControlUiState(
                        isPlaying = isPlaying,
                        canSkipPrevious = currentTrack != null,
                        canSkipNext = currentTrack != null,
                        canPlayPause = currentTrack != null,
                        isShuffleEnabled = isShuffle,
                        repeatMode = repeatMode,
                        isLoading = if (sessionState.isRemoteActive) {
                            sessionState.lastRemoteCommand?.status == org.michimusic.mobile.playback.RemoteCommandStatus.PENDING
                        } else {
                            playerState.playbackStatus is org.michimusic.player.PlaybackStatus.Buffering
                        },
                    ),
                    onPlayPause = { sessionManager.playPause() },
                    onSkipPrevious = { sessionManager.skipPrevious() },
                    onSkipNext = { sessionManager.skipNext() },
                    onToggleShuffle = { sessionManager.toggleShuffle() },
                    onCycleRepeat = { sessionManager.cycleRepeatMode() },
                    playPauseTestTag = "now_playing_play_pause",
                    prevTestTag = "now_playing_prev",
                    nextTestTag = "now_playing_next",
                    shuffleTestTag = "now_playing_shuffle",
                    repeatTestTag = "now_playing_repeat",
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                // Bottom Pill Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Up Next Capsule Pill
                    val queue = if (sessionState.isRemoteActive) sessionState.queue else playerState.queue
                    val currentIndex = currentTrack?.let { cur -> queue.indexOfFirst { it.id == cur.id } } ?: -1
                    val nextTrack = if (currentIndex in queue.indices && currentIndex + 1 < queue.size) {
                        queue[currentIndex + 1]
                    } else null

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = raised.fill,
                        border = androidx.compose.foundation.BorderStroke(raised.borderWidth, raised.outline),
                        shadowElevation = raised.elevation,
                        modifier = Modifier
                            .heightIn(min = MichiSpacing.minTouchTarget)
                            .weight(1f)
                            .clickable(role = Role.Button) { showQueueDialog = true },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = TertiaryCyan,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = if (nextTrack != null) "Siguiente: ${nextTrack.title}" else if (queue.isNotEmpty()) "${queue.size} en cola" else "Cola vacía",
                                style = MichiTypography.metadata,
                                color = PureWhite,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))
                    // Equalizer Button
                    MichiIconAction(
                        icon = Icons.Filled.Equalizer,
                        contentDescription = "Abrir ecualizador",
                        onClick = { showEqualizerDialog = true },
                        tint = PureWhite,
                        iconSize = 24.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                        testTag = "equalizer_button",
                    )
                }
            }
        }

        // Dialogs
        if (showAudioRouteDialog) {
            org.michimusic.mobile.ui.components.AudioRouteDialog(onDismiss = { showAudioRouteDialog = false })
        }

        if (showQueueDialog) {
            val q = if (sessionState.isRemoteActive) sessionState.queue else playerState.queue
            QueueDialog(
                queue = q,
                currentTrack = currentTrack,
                onTrackSelect = { selected ->
                    val idx = q.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
                    sessionManager.skipToQueueIndex(idx)
                },
                onDismiss = { showQueueDialog = false },
            )
        }

        if (showEqualizerDialog) {
            EqualizerDialog(onDismiss = { showEqualizerDialog = false })
        }

        if (showSignalTruthDialog) {
            SignalTruthDialog(
                observation = signalObservation,
                activeEndpoint = sessionState.activeEndpoint,
                currentTrack = currentTrack,
                isRemote = sessionState.isRemoteActive,
                onDismiss = { showSignalTruthDialog = false },
            )
        }

        if (showTrackActionMenu && currentTrack != null) {
            TrackActionBottomSheet(
                track = currentTrack,
                isFavorite = favoriteIds.contains(currentTrack.id),
                onDismiss = { showTrackActionMenu = false },
                onPlayNow = {
                    sessionManager.playNow(currentTrack)
                },
                onPlayNext = {
                    sessionManager.playNext(currentTrack)
                },
                onAddToQueue = {
                    sessionManager.addToQueue(currentTrack)
                },
                onAddToPlaylist = {
                    showAddToPlaylistDialog = true
                },
                onToggleFavorite = {
                    coroutineScope.launch {
                        favoritesRepo.toggleFavorite(currentTrack.id)
                    }
                },
                onViewSignalTruth = {
                    showSignalTruthDialog = true
                },
            )
        }

        if (showAddToPlaylistDialog && currentTrack != null) {
            AddToPlaylistDialog(
                tracks = listOf(currentTrack),
                onDismiss = { showAddToPlaylistDialog = false },
            )
        }
    }
}

@Composable
private fun SignalTruthCard(
    label: String,
    tag: String,
    tagColor: Color,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(0.75.dp, GlassBorderLow),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    color = OnSurfaceVariant,
                    style = MichiTypography.metadata,
                )
                Text(
                    text = tag,
                    color = tagColor,
                    style = MichiTypography.microLabel,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = value,
                color = PureWhite,
                style = MichiTypography.sectionTitle.copy(fontSize = 17.sp),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SignalTruthDialog(
    observation: org.michimusic.core.models.AudioSignalObservation,
    activeEndpoint: org.michimusic.mobile.playback.PlaybackEndpoint,
    currentTrack: org.michimusic.core.models.Track?,
    isRemote: Boolean,
    onDismiss: () -> Unit,
) {
    val floating = aeroMaterialStyle(AeroSurfaceRole.Floating, LocalVisualPreferences.current.highContrast)
    val provenanceItems = remember(observation, activeEndpoint, currentTrack, isRemote) {
        SignalTruthResolver.resolveProvenance(
            observation = observation,
            activeEndpoint = activeEndpoint,
            currentTrack = currentTrack,
            isRemote = isRemote,
        )
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = floating.fill,
            border = androidx.compose.foundation.BorderStroke(floating.borderWidth, floating.outline),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 580.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Signal Truth",
                            color = PureWhite,
                            style = MichiTypography.sectionTitle,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Procedencia de la señal",
                            color = OnSurfaceVariant,
                            style = MichiTypography.metadata,
                        )
                    }
                    MichiIconAction(
                        icon = Icons.Filled.Close,
                        contentDescription = "Cerrar detalles de señal",
                        onClick = onDismiss,
                        tint = OnSurfaceVariant,
                        iconSize = 20.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                    )
                }

                HorizontalDivider(color = GlassBorderLow)

                provenanceItems.forEach { item ->
                    val (tagText, tagColor) = when (item.evidenceType) {
                        org.michimusic.core.models.SignalEvidenceType.OBSERVED -> "Observado" to TertiaryCyan
                        org.michimusic.core.models.SignalEvidenceType.DECLARED -> "Declarado" to OnSurfaceVariant
                        org.michimusic.core.models.SignalEvidenceType.CONFIGURED -> "Configurado" to OnSurfaceVariant
                        org.michimusic.core.models.SignalEvidenceType.UNKNOWN -> "Desconocido" to Color(0xFFE5A93C)
                    }
                    SignalTruthCard(
                        label = item.label,
                        tag = tagText,
                        tagColor = tagColor,
                        value = item.value,
                    )
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "Los metadatos no certifican la salida física.",
                    color = OnSurfaceVariant,
                    style = MichiTypography.microLabel,
                )

                if (isRemote) {
                    val isStream = !activeEndpoint.isLocal &&
                        (activeEndpoint.type == EndpointType.STREAM_RECEIVER || activeEndpoint.capabilities.contains("AUDIO_OUTPUT"))
                    val remoteNote = if (isStream) {
                        "Michi Stream activo: decodificación PCM local transmitida por red; salida física en receptor externo."
                    } else {
                        "Reproducción remota activa: las medidas locales no describen la señal recibida por el receptor externo."
                    }
                    Text(
                        text = remoteNote,
                        color = OnSurfaceVariant.copy(alpha = 0.8f),
                        style = MichiTypography.microLabel,
                    )
                }
            }
        }
    }
}
