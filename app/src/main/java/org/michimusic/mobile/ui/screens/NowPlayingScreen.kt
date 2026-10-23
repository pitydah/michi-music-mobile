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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.EqualizerDialog
import org.michimusic.mobile.ui.components.GradientProgressBar
import org.michimusic.mobile.ui.components.QueueDialog
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatRemainingTimeSeconds
import org.michimusic.mobile.ui.components.formatTimeSeconds
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
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
    val activeEndpoint = sessionState.activeEndpoint

    val audioController: AudioController = koinInject()
    val favoritesRepo: FavoritesRepository = koinInject()
    val coroutineScope = rememberCoroutineScope()
    val favoriteIds by favoritesRepo.observeFavoriteTrackIds().collectAsState(initial = emptySet())
    val playerState by audioController.state.collectAsState()

    val currentTrack = sessionState.currentTrack ?: playerState.currentTrack
    val isPlaying = sessionState.isPlaying
    val positionSeconds = (sessionState.position / 1000f).coerceAtLeast(0f)
    val durationSeconds = (sessionState.duration / 1000f).coerceAtLeast(1f)

    val isShuffle = if (sessionState.isRemoteActive) sessionState.shuffleMode == 1 else playerState.shuffleMode
    val repeatMode = if (sessionState.isRemoteActive) sessionState.repeatMode else playerState.repeatMode
    val signalTracker: org.michimusic.player.SignalTruthTracker? = runCatching { koinInject<org.michimusic.player.SignalTruthTracker>() }.getOrNull()
    val signalObservation by (signalTracker?.observation?.collectAsState() ?: remember {
        mutableStateOf(org.michimusic.core.models.AudioSignalObservation())
    })

    var showQueueDialog by remember { mutableStateOf(false) }
    var showEqualizerDialog by remember { mutableStateOf(false) }
    var showAudioRouteDialog by remember { mutableStateOf(false) }
    var showSignalTruthDialog by remember { mutableStateOf(false) }
    var showTrackActionMenu by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
            .testTag("now_playing_screen"),
    ) {
        // Subtle Hi-Fi Ambient Depth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        color = PrimaryPinkContainer.copy(alpha = 0.08f),
                        radius = size.width * 0.5f,
                        center = Offset(size.width * 0.3f, size.height * 0.3f),
                    )
                    drawCircle(
                        color = TertiaryCyan.copy(alpha = 0.06f),
                        radius = size.width * 0.6f,
                        center = Offset(size.width * 0.7f, size.height * 0.7f),
                    )
                },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(MichiSpacing.minTouchTarget)
                        .clip(CircleShape)
                        .background(GlassFillLow)
                        .testTag("now_playing_close_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = "Colapsar",
                        tint = PureWhite,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "REPRODUCIENDO AHORA",
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

                    IconButton(
                        onClick = {
                            val next = cycleSleepTimer(sleepTimerRemaining)
                            if (next <= 0L) audioController.cancelSleepTimer() else audioController.setSleepTimer(next)
                        },
                        modifier = Modifier
                            .size(MichiSpacing.minTouchTarget)
                            .clip(CircleShape)
                            .background(if (sleepActive) TertiaryCyan.copy(alpha = 0.2f) else GlassFillLow)
                            .border(1.dp, if (sleepActive) TertiaryCyan else GlassBorderLow, CircleShape)
                            .testTag("sleep_timer_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = "Temporizador de Apagado",
                            tint = if (sleepActive) TertiaryCyan else PureWhite,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    IconButton(
                        onClick = { showQueueDialog = true },
                        modifier = Modifier
                            .size(MichiSpacing.minTouchTarget)
                            .clip(CircleShape)
                            .background(GlassFillLow)
                            .testTag("now_playing_queue_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Cola de Reproducción",
                            tint = PureWhite,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    IconButton(
                        onClick = { showTrackActionMenu = true },
                        modifier = Modifier
                            .size(MichiSpacing.minTouchTarget)
                            .clip(CircleShape)
                            .background(GlassFillLow)
                            .testTag("now_playing_more_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Más opciones",
                            tint = PureWhite,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // Central Album Artwork Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .aspectRatio(1f)
                        .clip(MichiShapes.lg)
                        .border(1.dp, GlassBorderHigh, MichiShapes.lg)
                        .testTag("now_playing_album_art"),
                ) {
                    AlbumArtView(
                        coverStyle = coverStyleFor(currentTrack?.coverId?.ifEmpty { currentTrack.title } ?: currentTrack?.title),
                        imageModel = currentTrack?.filepath?.ifEmpty { currentTrack.coverId } ?: currentTrack?.coverId,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 24.dp,
                        borderWidth = 0.dp,
                    )
                }
            }

            // Track Info & Destination Capsule
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentTrack?.title ?: "Sin reproducción activa",
                            style = MichiTypography.screenTitle,
                            maxLines = 1,
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

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Signal Truth pill
                        val decoded = signalObservation.decodedFormat
                        val label = if (decoded != null && decoded.sampleRate > 0) {
                            "${decoded.bitDepth}b/${decoded.sampleRate / 1000}k"
                        } else {
                            "Truth"
                        }
                        Box(
                            modifier = Modifier
                                .clip(MichiShapes.pill)
                                .background(TertiaryCyan.copy(alpha = 0.12f))
                                .border(1.dp, TertiaryCyan.copy(alpha = 0.35f), MichiShapes.pill)
                                .clickable { showSignalTruthDialog = true }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("signal_truth_button"),
                        ) {
                            Text(
                                text = label,
                                color = TertiaryCyan,
                                style = MichiTypography.microLabel,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        // Real active endpoint selector capsule
                        Box(
                            modifier = Modifier
                                .clip(MichiShapes.pill)
                                .background(GlassFillLow)
                                .border(1.dp, GlassBorderLow, MichiShapes.pill)
                                .clickable { showAudioRouteDialog = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("audio_route_button"),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Headphones,
                                    contentDescription = "Salidas de Audio",
                                    tint = if (activeEndpoint.isLocal) TertiaryCyan else PrimaryPinkContainer,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    text = activeEndpoint.name,
                                    color = PureWhite,
                                    style = MichiTypography.microLabel,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Icon(
                                    imageVector = Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = OnSurfaceVariant,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
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
                            text = formatTimeSeconds(positionSeconds),
                            style = MichiTypography.microLabel,
                            color = OnSurfaceVariant,
                        )
                        Text(
                            text = formatRemainingTimeSeconds(positionSeconds, durationSeconds),
                            style = MichiTypography.microLabel,
                            color = OnSurfaceVariant,
                        )
                    }
                }

                // Main Playback Controls
                org.michimusic.mobile.ui.components.premium.MichiPlaybackControls(
                    state = org.michimusic.mobile.ui.components.premium.PlaybackControlUiState(
                        isPlaying = isPlaying,
                        canSkipPrevious = true,
                        canSkipNext = true,
                        isShuffleEnabled = isShuffle,
                        repeatMode = repeatMode,
                        isLoading = playerState.playbackStatus is org.michimusic.player.PlaybackStatus.Buffering,
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
                        color = GlassFillLow,
                        border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderLow),
                        modifier = Modifier
                            .height(40.dp)
                            .clickable { showQueueDialog = true },
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
                                style = MichiTypography.microLabel,
                                color = PureWhite,
                                maxLines = 1,
                            )
                        }
                    }

                    // Equalizer Button
                    IconButton(
                        onClick = { showEqualizerDialog = true },
                        modifier = Modifier
                            .size(MichiSpacing.minTouchTarget)
                            .clip(CircleShape)
                            .background(GlassFillLow)
                            .border(1.dp, GlassBorderLow, CircleShape)
                            .testTag("equalizer_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Equalizer,
                            contentDescription = "Ecualizador",
                            tint = OnSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
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
private fun SignalTruthDialog(
    observation: org.michimusic.core.models.AudioSignalObservation,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceObsidian,
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Verdad de la Señal (Signal Truth)",
                        color = PureWhite,
                        style = MichiTypography.sectionTitle,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = OnSurfaceVariant)
                    }
                }

                HorizontalDivider(color = GlassBorderLow)

                SignalTruthRow("Fuente Original", observation.sourceFormat?.let { "${it.codec} • ${if (it.sampleRate > 0) "${it.sampleRate} Hz" else "Header"} • ${it.channels} ch" } ?: "No disponible")
                SignalTruthRow("Decodificado (PCM)", observation.decodedFormat?.let { "${it.bitDepth}-bit / ${it.sampleRate} Hz • ${it.channels} ch" } ?: "No decodificado / Desconocido")
                SignalTruthRow("Procesamiento DSP", if (observation.dspStatus.isEqActive) "Activo (EQ/FX)" else "Bypass / Neutro")
                SignalTruthRow("ReplayGain", if (observation.dspStatus.isReplayGainActive) "Aplicado (${"%.1f".format(observation.dspStatus.replayGainAppliedDb)} dB)" else "Inactivo / Bypass")
                SignalTruthRow("Enrutamiento", observation.routeStatus)
                SignalTruthRow("Salida AudioTrack", observation.outputConfig?.let { "${it.bitDepth}-bit / ${it.sampleRate} Hz • ${it.channels} ch" } ?: "No verificable")
            }
        }
    }
}

@Composable
private fun SignalTruthRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = OnSurfaceVariant, fontSize = 11.sp)
        Text(text = value, color = PureWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
