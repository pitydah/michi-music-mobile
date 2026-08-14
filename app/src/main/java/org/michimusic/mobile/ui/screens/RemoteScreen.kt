package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.michimusic.mobile.remote.RemoteConnectionState
import org.michimusic.mobile.remote.RemoteViewModel
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.EqualizerDialog
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.GlassOverlayCard
import org.michimusic.mobile.ui.components.GradientProgressBar
import org.michimusic.mobile.ui.components.PulsingDot
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatRemainingTimeSeconds
import org.michimusic.mobile.ui.components.formatTimeSeconds
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TertiaryCyanContainer

@Composable
fun RemoteScreen(
    onNavigateToSync: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: RemoteViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.connectIfNeeded()
    }

    val playerState = state.playerState
    val currentTitle = playerState.effectiveTitle.ifEmpty { "Escritorio Inactivo" }
    val currentArtist = playerState.effectiveArtist.ifEmpty { "Sin pista en reproducción" }
    val currentAlbum = playerState.album.ifEmpty { "Michi Desktop" }
    val isPlaying = playerState.effectiveState == "playing"
    val positionSeconds = (playerState.effectivePosition / 1000f).coerceAtLeast(0f)
    val durationSeconds = (playerState.effectiveDuration / 1000f).coerceAtLeast(1f)
    var isShuffle by remember { mutableStateOf(false) }
    var isRepeat by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceObsidian),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onNavigateToSync,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = "Sync",
                        tint = PrimaryPink,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Text(
                    text = "Control Remoto",
                    color = PrimaryPink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    modifier = Modifier.testTag("remote_title"),
                )

                IconButton(
                    onClick = { showEqualizer = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Buscar / EQ",
                        tint = PrimaryPink,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("remote_scroll_list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Connection Status Indicator Pill
                item {
                    val isConnected = state.connState == RemoteConnectionState.CONNECTED
                    val sourceTitle = state.sourceName.ifEmpty { "Servidor Michi Desktop" }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(GlassFillHigh)
                            .border(
                                1.dp,
                                if (isConnected) TertiaryCyan.copy(alpha = 0.5f) else GlassBorderLow,
                                RoundedCornerShape(20.dp),
                            )
                            .clickable { onNavigateToSync() }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("connection_status_indicator"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PulsingDot(
                                color = if (isConnected) TertiaryCyan else PrimaryPinkContainer,
                                size = 8.dp,
                            )
                            Text(
                                text = if (isConnected) "CONECTADO A ${sourceTitle.uppercase()}" else "SIN CONEXIÓN REMOTA",
                                color = if (isConnected) TertiaryCyan else PrimaryPink,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                            )
                        }
                    }
                }

                // Now Playing Glass Card
                item {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("remote_now_playing_card"),
                        shape = RoundedCornerShape(16.dp),
                        backgroundColor = GlassFillHigh,
                        borderColor = GlassBorderHigh,
                        glowColor = TertiaryCyan,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, GlassBorderHigh, RoundedCornerShape(12.dp)),
                                ) {
                                    AlbumArtView(
                                        coverStyle = coverStyleFor(playerState.effectiveCoverId.ifEmpty { currentTitle }),
                                        imageModel = playerState.coverUrl.ifEmpty { playerState.effectiveCoverId },
                                        modifier = Modifier.fillMaxSize(),
                                        cornerRadius = 12.dp,
                                    )
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(
                                        text = currentTitle,
                                        color = PureWhite,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = currentArtist,
                                        color = TertiaryCyan,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "$currentAlbum • Remoto",
                                        color = OnSurfaceVariant.copy(alpha = 0.7f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 1.5.sp,
                                    )
                                }
                            }

                            // Track Progress Bar
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = formatTimeSeconds(positionSeconds),
                                        color = OnSurfaceVariant,
                                        fontSize = 11.sp,
                                    )
                                    Text(
                                        text = formatRemainingTimeSeconds(positionSeconds, durationSeconds),
                                        color = OnSurfaceVariant,
                                        fontSize = 11.sp,
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                GradientProgressBar(
                                    currentValue = positionSeconds,
                                    maxValue = durationSeconds,
                                    onSeek = { seekSec ->
                                        viewModel.seek((seekSec * 1000).toLong())
                                    },
                                    trackHeight = 6.dp,
                                    showThumb = true,
                                )
                            }
                        }
                    }
                }

                // Main Transport Controls Glass Overlay
                item {
                    GlassOverlayCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("remote_transport_overlay"),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // Primary 3 Transport Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = { viewModel.previous() },
                                    modifier = Modifier
                                        .size(56.dp)
                                        .testTag("remote_skip_prev"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SkipPrevious,
                                        contentDescription = "Anterior",
                                        tint = PureWhite,
                                        modifier = Modifier.size(44.dp),
                                    )
                                }

                                IconButton(
                                    onClick = { viewModel.togglePlayPause() },
                                    modifier = Modifier
                                        .size(80.dp)
                                        .drawBehind {
                                            drawCircle(
                                                color = PrimaryPinkContainer.copy(alpha = 0.3f),
                                                radius = 44.dp.toPx(),
                                            )
                                        }
                                        .clip(CircleShape)
                                        .background(Color(0x1AFFFFFF))
                                        .testTag("remote_play_pause"),
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (isPlaying) "Pausa" else "Reproducir",
                                        tint = PureWhite,
                                        modifier = Modifier.size(54.dp),
                                    )
                                }

                                IconButton(
                                    onClick = { viewModel.next() },
                                    modifier = Modifier
                                        .size(56.dp)
                                        .testTag("remote_skip_next"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SkipNext,
                                        contentDescription = "Siguiente",
                                        tint = PureWhite,
                                        modifier = Modifier.size(44.dp),
                                    )
                                }
                            }

                            // Volume Slider Control
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VolumeDown,
                                    contentDescription = "Bajar Volumen",
                                    tint = OnSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )

                                Slider(
                                    value = state.playerState.volume.toFloat(),
                                    onValueChange = { viewModel.setVolume(it.toInt()) },
                                    valueRange = 0f..100f,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("remote_volume_slider"),
                                    colors = SliderDefaults.colors(
                                        thumbColor = TertiaryCyan,
                                        activeTrackColor = TertiaryCyanContainer,
                                        inactiveTrackColor = Color(0x33FFFFFF),
                                    ),
                                )

                                Icon(
                                    imageVector = Icons.Filled.VolumeUp,
                                    contentDescription = "Subir Volumen",
                                    tint = OnSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color(0x11FFFFFF)),
                            )

                            // Secondary Actions
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clickable { isShuffle = !isShuffle }
                                        .padding(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Shuffle,
                                        contentDescription = "Aleatorio",
                                        tint = if (isShuffle) TertiaryCyan else OnSurfaceVariant,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Aleatorio",
                                        color = if (isShuffle) TertiaryCyan else OnSurfaceVariant,
                                        fontSize = 10.sp,
                                    )
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clickable { isRepeat = !isRepeat }
                                        .padding(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Repeat,
                                        contentDescription = "Repetir",
                                        tint = if (isRepeat) TertiaryCyan else OnSurfaceVariant,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Repetir",
                                        color = if (isRepeat) TertiaryCyan else OnSurfaceVariant,
                                        fontSize = 10.sp,
                                        fontWeight = if (isRepeat) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clickable { onNavigateToSync() }
                                        .padding(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.QueueMusic,
                                        contentDescription = "Dispositivos",
                                        tint = OnSurfaceVariant,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Dispositivos",
                                        color = OnSurfaceVariant,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showEqualizer) {
            EqualizerDialog(onDismiss = { showEqualizer = false })
        }
    }
}
