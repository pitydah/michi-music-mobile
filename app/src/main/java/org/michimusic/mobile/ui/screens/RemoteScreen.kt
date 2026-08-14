package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.GradientProgressBar
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatRemainingTimeSeconds
import org.michimusic.mobile.ui.components.formatTimeSeconds
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurpleDeep
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

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

    val isConnected = state.connState == RemoteConnectionState.CONNECTED
    val playerState = state.playerState
    val currentTitle = playerState.effectiveTitle.ifEmpty { "Escritorio Inactivo" }
    val currentArtist = playerState.effectiveArtist.ifEmpty { "Sin pista en reproducción" }
    val currentAlbum = playerState.album.ifEmpty { "Michi Desktop" }
    val isPlaying = playerState.effectiveState == "playing"
    val positionSeconds = (playerState.effectivePosition / 1000f).coerceAtLeast(0f)
    val durationSeconds = (playerState.effectiveDuration / 1000f).coerceAtLeast(1f)

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
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(GlassFillLow),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = "Sincronización y Dispositivos",
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

                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) TertiaryCyan else TextMuted),
                )
            }

            if (!isConnected) {
                // Disconnected Empty State
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SettingsRemote,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(64.dp),
                        )
                        Text(
                            text = "No hay dispositivo conectado",
                            color = PureWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Conecta Michi Music a tu servidor de escritorio para controlar la reproducción de forma remota.",
                            color = TextMuted,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(PrimaryPinkContainer)
                                .clickable { onNavigateToSync() }
                                .padding(horizontal = 24.dp, vertical = 14.dp)
                                .testTag("remote_connect_button"),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Filled.WifiTethering, contentDescription = null, tint = PureWhite, modifier = Modifier.size(20.dp))
                                Text("Buscar Dispositivos", color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
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
                        val sourceTitle = state.sourceName.ifEmpty { "Servidor Michi Desktop" }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(GlassFillHigh)
                                .border(1.dp, TertiaryCyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                .clickable { onNavigateToSync() }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("connection_status_indicator"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(TertiaryCyan),
                                )
                                Text(
                                    text = "Conectado a: $sourceTitle",
                                    color = TertiaryCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    // Remote Album Artwork
                    item {
                        Box(
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(GlassFillLow)
                                .border(1.dp, GlassBorderHigh, RoundedCornerShape(28.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            AlbumArtView(
                                coverStyle = coverStyleFor(currentTitle),
                                imageModel = null,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 28.dp,
                            )
                        }
                    }

                    // Track Info
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = currentTitle,
                                color = PureWhite,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "$currentArtist • $currentAlbum",
                                color = OnSurfaceVariant,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Progress Bar
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            GradientProgressBar(
                                currentValue = positionSeconds,
                                maxValue = durationSeconds,
                                onSeek = { posSec ->
                                    viewModel.seek((posSec * 1000).toLong())
                                },
                                trackHeight = 6.dp,
                                showThumb = true,
                            )

                            Spacer(Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(formatTimeSeconds(positionSeconds), color = TextMuted, fontSize = 11.sp)
                                Text(formatRemainingTimeSeconds(positionSeconds, durationSeconds), color = TextMuted, fontSize = 11.sp)
                            }
                        }
                    }

                    // Playback Controls
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = { viewModel.previous() },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Filled.SkipPrevious, contentDescription = "Anterior", tint = PureWhite, modifier = Modifier.size(32.dp))
                            }

                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(listOf(PrimaryPinkContainer, SecondaryPurpleDeep)),
                                    )
                                    .clickable { viewModel.togglePlayPause() }
                                    .testTag("remote_play_pause"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                    tint = PureWhite,
                                    modifier = Modifier.size(36.dp),
                                )
                            }

                            IconButton(
                                onClick = { viewModel.next() },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(Icons.Filled.SkipNext, contentDescription = "Siguiente", tint = PureWhite, modifier = Modifier.size(32.dp))
                            }
                        }
                    }

                    // Volume Slider
                    item {
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            backgroundColor = GlassFillLow,
                            borderColor = GlassBorderLow,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Icons.Filled.VolumeDown, contentDescription = null, tint = OnSurfaceVariant, modifier = Modifier.size(20.dp))
                                Slider(
                                    value = playerState.effectiveVolume.toFloat(),
                                    onValueChange = { viewModel.setVolume(it.toInt()) },
                                    valueRange = 0f..100f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = TertiaryCyan,
                                        activeTrackColor = TertiaryCyan,
                                        inactiveTrackColor = GlassFillHigh,
                                    ),
                                )
                                Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = OnSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(100.dp))
                    }
                }
            }
        }
    }
}
