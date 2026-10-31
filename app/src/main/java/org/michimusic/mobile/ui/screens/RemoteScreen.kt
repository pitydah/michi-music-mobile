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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.michimusic.mobile.remote.RemoteConnectionState
import org.michimusic.mobile.remote.RemoteUiState
import org.michimusic.mobile.remote.RemoteViewModel
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.GradientProgressBar
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatRemainingTimeSeconds
import org.michimusic.mobile.ui.components.formatTimeSeconds
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted

private val AmberPendingColor = Color(0xFFE5A93C)
private val AmberPendingBg = Color(0x26E5A93C)
private val AmberPendingBorder = Color(0x66E5A93C)

@Composable
fun RemoteScreen(
    onNavigateToSync: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: RemoteViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var handoffMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.connectIfNeeded()
    }

    RemoteScreenContent(
        state = state,
        onNavigateToSync = onNavigateToSync,
        onBack = onBack,
        onPlayPause = { viewModel.togglePlayPause() },
        onSkipPrevious = { viewModel.previous() },
        onSkipNext = { viewModel.next() },
        onSeek = { posSec -> viewModel.seek((posSec * 1000).toLong()) },
        onHandoff = {
            viewModel.handoffToLocal { _, msg ->
                handoffMessage = msg
            }
        },
        handoffMessage = handoffMessage,
        modifier = modifier,
    )
}

@Composable
fun RemoteScreenContent(
    state: RemoteUiState,
    onNavigateToSync: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onHandoff: () -> Unit,
    handoffMessage: String?,
    modifier: Modifier = Modifier,
) {
    val isConnected = state.connState == RemoteConnectionState.CONNECTED
    val playerState = state.playerState
    val currentTitle = playerState.effectiveTitle.ifEmpty { "Escritorio Inactivo" }
    val currentArtist = playerState.effectiveArtist.ifEmpty { "Sin pista en reproducción" }
    val currentAlbum = playerState.album.ifEmpty { "Michi Desktop" }
    val isPlaying = playerState.effectiveState == "playing"
    val positionSeconds = (playerState.effectivePosition / 1000f).coerceAtLeast(0f)
    val durationSeconds = (playerState.effectiveDuration / 1000f).coerceAtLeast(0f)

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
            // Editorial Header with Back, Title/Subtitle, and MoreVert
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = MichiSpacing.screenHorizontal),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MichiIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    onClick = onBack,
                    tint = PureWhite,
                    iconSize = 24.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = if (isConnected) state.sourceName else "Michi Control",
                        color = PureWhite,
                        style = MichiTypography.sectionTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("remote_title"),
                    )

                    if (isConnected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(TertiaryCyan),
                            )
                            Text(
                                text = "Conectado",
                                color = TertiaryCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    } else {
                        Text(
                            text = "Sin conexión",
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                MichiIconAction(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "Opciones del dispositivo",
                    onClick = onNavigateToSync,
                    tint = PureWhite,
                    iconSize = 24.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                )
            }

            if (!isConnected) {
                // Disconnected Empty State
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 24.dp),
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
                            text = "Conecta Michi Music a tu servidor de escritorio o receptor para controlar la reproducción de forma remota.",
                            color = TextMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
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
                                Icon(
                                    imageVector = Icons.Filled.WifiTethering,
                                    contentDescription = null,
                                    tint = PureWhite,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = "Buscar Dispositivos",
                                    color = PureWhite,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("remote_scroll_list"),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Pending Command Banner
                    if (state.pendingCommand) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AmberPendingBg)
                                    .border(0.75.dp, AmberPendingBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(AmberPendingColor),
                                    )
                                    Text(
                                        text = state.pendingCommandMessage ?: "Orden pendiente; espera confirmación",
                                        color = AmberPendingColor,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }

                    // Connection Status Indicator Pill
                    item {
                        val sourceTitle = state.sourceName.ifEmpty { "Servidor Michi Desktop" }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(AeroGraphiteRaised)
                                .border(0.75.dp, AeroHairline, RoundedCornerShape(20.dp))
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
                                    text = "Controlando: $sourceTitle",
                                    color = TertiaryCyan,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Hero Cover Art
                    item {
                        Box(
                            modifier = Modifier
                                .size(260.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(AeroGraphiteRaised)
                                .border(0.75.dp, AeroHairline, RoundedCornerShape(28.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            AlbumArtView(
                                coverStyle = coverStyleFor(currentTitle),
                                imageModel = null, // Remote endpoint does not expose trusted artwork in this model.
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 28.dp,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Carátula no disponible desde el servidor",
                            color = TextMuted,
                            fontSize = 12.sp,
                        )
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
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = "$currentArtist • $currentAlbum",
                                color = OnSurfaceVariant,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    // Progress Bar
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            GradientProgressBar(
                                currentValue = positionSeconds,
                                maxValue = durationSeconds,
                                onSeek = onSeek,
                                trackHeight = 6.dp,
                                showThumb = true,
                            )

                            Spacer(Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = formatTimeSeconds(positionSeconds),
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    text = if (durationSeconds > 0f) {
                                        formatRemainingTimeSeconds(positionSeconds, durationSeconds)
                                    } else {
                                        "--:--"
                                    },
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }

                    // Naked Transport Controls (Pure naked pictograms, no circular ripple discs/rings)
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MichiIconAction(
                                icon = Icons.Filled.SkipPrevious,
                                contentDescription = "Pista anterior",
                                onClick = onSkipPrevious,
                                tint = PureWhite,
                                iconSize = 34.dp,
                                minTargetSize = 48.dp,
                                testTag = "remote_prev",
                            )

                            MichiIconAction(
                                icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                onClick = onPlayPause,
                                tint = PureWhite,
                                iconSize = 56.dp,
                                minTargetSize = 72.dp,
                                testTag = "remote_play_pause",
                            )

                            MichiIconAction(
                                icon = Icons.Filled.SkipNext,
                                contentDescription = "Pista siguiente",
                                onClick = onSkipNext,
                                tint = PureWhite,
                                iconSize = 34.dp,
                                minTargetSize = 48.dp,
                                testTag = "remote_next",
                            )
                        }
                    }

                    // Selected Output Card
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(AeroGraphiteRaised)
                                .border(0.75.dp, AeroHairline, RoundedCornerShape(16.dp))
                                .padding(16.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(SurfaceObsidian)
                                        .border(0.75.dp, AeroHairline, RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Speaker,
                                        contentDescription = null,
                                        tint = TertiaryCyan,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "SALIDA SELECCIONADA",
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                    )
                                    Text(
                                        text = state.sourceName.ifEmpty { "Dispositivo remoto" },
                                        color = PureWhite,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0x1A00E5FF))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = "Activo",
                                        color = TertiaryCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }

                    // Handoff to This Phone
                    item {
                        Button(
                            onClick = onHandoff,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("remote_handoff_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PhoneAndroid,
                                    contentDescription = null,
                                    tint = PureWhite,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = "Continuar en este teléfono",
                                    color = PureWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                            }
                        }

                        if (handoffMessage != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = handoffMessage,
                                color = TertiaryCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }

                    item {
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}
