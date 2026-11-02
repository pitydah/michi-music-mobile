package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.michimusic.mobile.playback.EndpointType
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.AeroSignalRouteStrip
import org.michimusic.mobile.ui.components.EqualizerDialog
import org.michimusic.mobile.ui.components.GradientProgressBar
import org.michimusic.mobile.ui.components.shouldScrollNowPlaying
import org.michimusic.mobile.ui.components.scrollArtworkHeightDp
import org.michimusic.mobile.ui.components.decodedPcmLabel
import org.michimusic.mobile.ui.components.QueueDialog
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatTimeSeconds
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.SurfaceContainerLowest
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.aeroMaterialStyle
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.playback.SignalTruthResolver
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
    // Unknown duration/position must not reach slider geometry or time labels.
    val seekableDuration = durationSeconds.isFinite() && durationSeconds > 0f &&
        durationSeconds.toDouble() * 1000.0 < Long.MAX_VALUE.toDouble() && currentTrack != null
    val safeDurationSeconds = if (seekableDuration) durationSeconds else 0f
    val safePositionSeconds = if (seekableDuration && positionSeconds.isFinite())
        positionSeconds.coerceIn(0f, safeDurationSeconds) else 0f

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
    val compactLayout = configuration.screenHeightDp < 730 || configuration.fontScale > 1.15f || configuration.screenWidthDp < 340
    val scrollablePlayer = shouldScrollNowPlaying(configuration.screenHeightDp, configuration.fontScale) ||
        configuration.screenWidthDp < 340 || configuration.fontScale >= 1.3f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
            .testTag("now_playing_screen"),
    ) {
        // Controlled Aero plum surface; this is a stable UI palette, not a claim
        // that we extracted colors from the album bitmap or verified audio quality.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(
                    listOf(
                        Color(0xFF342638),
                        Color(0xFF261F2D),
                        Color(0xFF18171F),
                        SurfaceObsidian,
                    ),
                )),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = if (compactLayout) 18.dp else 24.dp, vertical = 10.dp)
                .then(if (scrollablePlayer) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(bottom = if (scrollablePlayer) 22.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactLayout) 12.dp else 16.dp),
        ) {
            val decoded = signalObservation.decodedFormat
            val streamReceiver = sessionState.isRemoteActive && !activeEndpoint.isLocal &&
                (activeEndpoint.type == EndpointType.STREAM_RECEIVER ||
                    activeEndpoint.capabilities.contains("AUDIO_OUTPUT"))
            val pcmLabel = if ((!sessionState.isRemoteActive || streamReceiver) &&
                decoded != null && decoded.sampleRate > 0 && decoded.bitDepth > 0) {
                decodedPcmLabel(decoded.sampleRate, decoded.bitDepth)
            } else null
            val qualityBadge = org.michimusic.mobile.ui.components.aeroNowPlayingQualityBadge(pcmLabel != null)

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
                    iconSize = 24.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                    testTag = "now_playing_close_button",
                )

                Surface(
                    shape = MichiShapes.pill,
                    color = Color(0x33FFFFFF),
                    border = androidx.compose.foundation.BorderStroke(0.75.dp, Color(0x4DFFFFFF)),
                    modifier = Modifier.testTag("now_playing_quality_badge"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(TertiaryCyan),
                        )
                        Text(
                            text = qualityBadge,
                            style = MichiTypography.microLabel,
                            color = PureWhite,
                        )
                    }
                }
            }

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (scrollablePlayer) Modifier.height(
                            scrollArtworkHeightDp(configuration.screenHeightDp).dp,
                        ) else Modifier.weight(1f),
                    )
                    .padding(top = if (compactLayout) 2.dp else 6.dp, bottom = if (compactLayout) 2.dp else 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                val artSide = org.michimusic.mobile.ui.components.aeroArtworkSideDp(maxWidth.value, maxHeight.value).dp
                Box(
                    modifier = Modifier
                        .size(artSide)
                        .shadow(
                            if (LocalVisualPreferences.current.highContrast) 0.dp else 12.dp,
                            RoundedCornerShape(22.dp),
                            clip = false,
                        )
                        .background(Color(0xFF1D1A27), RoundedCornerShape(22.dp))
                        .border(0.75.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    org.michimusic.mobile.ui.components.AeroArtworkMat(
                        modifier = Modifier
                            .size((artSide - 8.dp).coerceAtLeast(0.dp))
                            .testTag("now_playing_album_art"),
                        cornerRadius = 20.dp,
                        inset = if (compactLayout) 3.dp else 6.dp,
                    ) {
                        AlbumArtView(
                            coverStyle = coverStyleFor(currentTrack?.coverId?.ifEmpty { currentTrack.title } ?: currentTrack?.title),
                            imageModel = currentTrack?.coverId?.takeIf { it.isNotBlank() },
                            fallbackMediaUri = currentTrack?.filepath,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 20.dp,
                            borderWidth = 0.dp,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compactLayout) 6.dp else 8.dp),
            ) {
                Text(
                    text = currentTrack?.title ?: "Sin reproducción activa",
                    style = MichiTypography.screenTitle.copy(
                        fontSize = if (compactLayout) 24.sp else 29.sp,
                        lineHeight = if (compactLayout) 29.sp else 34.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.86f),
                )
                Text(
                    text = currentTrack?.artist ?: "Selecciona una canción",
                    style = MichiTypography.sectionTitle.copy(fontWeight = FontWeight.Normal),
                    color = Color(0xCCFFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.86f),
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                GradientProgressBar(
                    currentValue = safePositionSeconds,
                    maxValue = safeDurationSeconds,
                    onSeek = { seekSec ->
                        safeNowPlayingSeekMillis(seekSec, safeDurationSeconds, seekableDuration)?.let {
                            sessionManager.seekTo(it)
                        }
                    },
                    trackHeight = 4.dp,
                    showThumb = true,
                    testTag = "now_playing_seeker",
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (seekableDuration) formatTimeSeconds(safePositionSeconds) else "—:—",
                        style = MichiTypography.microLabel,
                        color = Color(0xCCFFFFFF),
                    )
                    Text(
                        text = if (seekableDuration) formatTimeSeconds(safeDurationSeconds) else "—:—",
                        style = MichiTypography.microLabel,
                        color = Color(0xCCFFFFFF),
                    )
                }
            }

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
                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isFav = currentTrack?.let { favoriteIds.contains(it.id) } == true

                MichiIconAction(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Abrir cola",
                    onClick = { showQueueDialog = true },
                    tint = PureWhite.copy(alpha = 0.82f),
                    iconSize = 22.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                    testTag = "now_playing_queue_button",
                )
                MichiIconAction(
                    icon = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFav) "Quitar de favoritos" else "Añadir a favoritos",
                    onClick = {
                        currentTrack?.let { track ->
                            coroutineScope.launch { favoritesRepo.toggleFavorite(track.id) }
                        }
                    },
                    enabled = currentTrack != null,
                    tint = if (isFav) PrimaryPinkContainer else PureWhite.copy(alpha = 0.82f),
                    iconSize = 22.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                    testTag = "now_playing_favorite_button",
                )
                MichiIconAction(
                    icon = Icons.Filled.Timer,
                    contentDescription = if (playerState.sleepTimerRemainingMs > 0L)
                        "Temporizador activo. Cambiar duración" else "Activar temporizador",
                    onClick = {
                        val next = cycleSleepTimer(playerState.sleepTimerRemainingMs)
                        if (next <= 0L) audioController.cancelSleepTimer()
                        else audioController.setSleepTimer(next)
                    },
                    tint = if (playerState.sleepTimerRemainingMs > 0L) TertiaryCyan else PureWhite.copy(alpha = .82f),
                    iconSize = 22.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                    testTag = "sleep_timer_button",
                )
                MichiIconAction(
                    icon = Icons.Filled.Equalizer,
                    contentDescription = "Abrir ecualizador",
                    onClick = { showEqualizerDialog = true },
                    tint = PureWhite.copy(alpha = 0.82f),
                    iconSize = 22.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                    testTag = "equalizer_button",
                )
                MichiIconAction(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = if (currentTrack != null) "Más opciones de la canción" else "Sin canción seleccionada",
                    onClick = { showTrackActionMenu = true },
                    enabled = currentTrack != null,
                    tint = if (currentTrack != null) PureWhite.copy(alpha = 0.82f) else OnSurfaceVariant,
                    iconSize = 22.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                    testTag = "now_playing_more_button",
                )
            }

            val queue = if (sessionState.isRemoteActive) sessionState.queue else playerState.queue
            val currentIndex = currentTrack?.let { cur -> queue.indexOfFirst { it.id == cur.id } } ?: -1
            val nextTrack = if (currentIndex in queue.indices && currentIndex + 1 < queue.size) {
                queue[currentIndex + 1]
            } else null

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                nextTrack?.let {
                    Text(
                        text = "Siguiente: ${it.title}",
                        style = MichiTypography.metadata,
                        color = Color(0xB3FFFFFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.9f),
                    )
                }

                AeroSignalRouteStrip(
                    signalLabel = pcmLabel ?: "Signal Truth",
                    signalDescription = if (pcmLabel != null)
                        "Señal PCM decodificada; la salida física no está certificada."
                    else "Consulta la señal observada. La salida física no está verificada.",
                    destination = presentation.endpointDescription,
                    onSignalClick = { showSignalTruthDialog = true },
                    onDestinationClick = { showAudioRouteDialog = true },
                    modifier = Modifier.testTag("m60_now_playing_signal_footer"),
                )

                sessionState.statusMessage?.let { status ->
                    Text(
                        text = status,
                        style = MichiTypography.microLabel,
                        color = if (sessionState.lastSessionError != null) PrimaryPinkContainer else Color(0xB3FFFFFF),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(0.9f),
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

                for (item in provenanceItems) {
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
