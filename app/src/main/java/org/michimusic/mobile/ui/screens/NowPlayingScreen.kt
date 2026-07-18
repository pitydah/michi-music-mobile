package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.michimusic.mobile.ui.screens.nowplaying.AlbumArtworkCard
import org.michimusic.mobile.ui.screens.nowplaying.FloatingControlsColumn
import org.michimusic.mobile.ui.screens.nowplaying.MediaControlsBar
import org.michimusic.mobile.ui.screens.nowplaying.MichiSlider
import org.michimusic.mobile.ui.screens.nowplaying.NowPlayingStage
import org.michimusic.mobile.ui.screens.nowplaying.PlayerBackdrop
import org.michimusic.mobile.ui.screens.nowplaying.PlaybackSource
import org.michimusic.mobile.ui.screens.nowplaying.PlaybackSourceDropdown
import org.michimusic.mobile.ui.screens.nowplaying.PlaybackSourceMenu
import org.michimusic.mobile.ui.screens.nowplaying.TrackInfo
import org.michimusic.mobile.ui.screens.nowplaying.UtilityIconRow
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.michiAccentFor
import org.michimusic.player.AudioController
import org.michimusic.player.PlayerState

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return "$min:${sec.toString().padStart(2, '0')}"
}

private fun formatTimer(remainingMs: Long): String {
    val totalMin = (remainingMs / 60000).toInt()
    val sec = ((remainingMs % 60000) / 1000).toInt()
    return "$totalMin:${sec.toString().padStart(2, '0')}"
}

private fun cycleSleepTimer(current: Long): Long = when {
    current <= 0L -> 15 * 60_000L
    current <= 15 * 60_000L -> 30 * 60_000L
    current <= 30 * 60_000L -> 60 * 60_000L
    else -> 0L
}

@Composable
fun NowPlayingScreen(
    onBack: () -> Unit = {},
) {
    val audioController: AudioController = koinInject()
    var playerState by remember { mutableStateOf(PlayerState()) }

    LaunchedEffect(audioController) {
        audioController.state.collect { playerState = it }
    }

    val track = playerState.currentTrack
    val accent = remember(track?.coverId, track?.title) {
        michiAccentFor(track?.coverId ?: track?.title)
    }

    val sleepTimerRemaining = playerState.sleepTimerRemainingMs
    val timerText = if (sleepTimerRemaining > 0L) formatTimer(sleepTimerRemaining) else null

    val sources = remember {
        listOf(
            PlaybackSource("local", "Este dispositivo", { /* icono por defecto */ }),
            PlaybackSource("server", "Servidor Michi", { /* icono */ }),
        )
    }
    var sourceExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerBackdrop(coverId = track?.coverId, accent = accent)

            NowPlayingStage(
                coverId = track?.coverId,
                isPlaying = playerState.isPlaying,
                accent = accent,
                onPlayPause = {
                    if (playerState.isPlaying) audioController.pause() else audioController.play()
                },
            ) {
                TrackInfo(
                    title = track?.title ?: "",
                    artist = track?.artist ?: "",
                    album = track?.album ?: "",
                )

                Spacer(Modifier.height(12.dp))

                MichiSlider(
                    value = if (playerState.duration > 0)
                        (playerState.position.toFloat() / playerState.duration.toFloat()).coerceIn(0f, 1f) else 0f,
                    onValueChange = { /* preview */ },
                    accent = accent,
                    timeStart = formatTime(playerState.position),
                    timeEnd = formatTime(playerState.duration),
                    onSeekEnd = { fraction ->
                        audioController.seekTo((fraction * playerState.duration).toLong())
                    },
                )

                Spacer(Modifier.height(8.dp))

                MediaControlsBar(
                    isPlaying = playerState.isPlaying,
                    onPlayPause = {
                        if (playerState.isPlaying) audioController.pause() else audioController.play()
                    },
                    onNext = { audioController.skipNext() },
                    onPrevious = { audioController.skipPrevious() },
                    accent = accent,
                )
            }

            FloatingControlsColumn {
                UtilityIconRow(
                    onBack = onBack,
                    sleepTimerActive = sleepTimerRemaining > 0L,
                    timerText = timerText,
                    onTimerClick = {
                        val next = cycleSleepTimer(sleepTimerRemaining)
                        if (next <= 0L) audioController.cancelSleepTimer()
                        else audioController.setSleepTimer(next)
                    },
                )

                Spacer(Modifier.weight(1f))

                PlaybackSourceDropdown(
                    sources = sources,
                    expanded = sourceExpanded,
                    onToggle = { sourceExpanded = !sourceExpanded },
                )
            }

            PlaybackSourceMenu(
                sources = sources,
                expanded = sourceExpanded,
                onDismiss = { sourceExpanded = false },
                onSelect = { sourceExpanded = false },
            )
        }
    }
}
