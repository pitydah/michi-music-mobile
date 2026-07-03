package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.PremiumButton
import org.michimusic.mobile.ui.components.PremiumEmptyState
import org.michimusic.mobile.ui.components.PremiumLoadingState
import org.michimusic.mobile.ui.components.PremiumScreen
import org.michimusic.mobile.ui.components.PremiumStatPill
import org.michimusic.mobile.ui.components.PremiumTrackItem
import org.michimusic.mobile.ui.components.ScreenHeader
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.AudioController

@Composable
fun HomeScreen(
    onNavigateToSearch: () -> Unit = {},
) {
    val viewModel: AlbumsViewModel = koinViewModel()
    val allTracks by viewModel.allTracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val controller: AudioController = koinInject()

    LaunchedEffect(Unit) { viewModel.loadMedia() }

    PremiumScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            ScreenHeader(
                title = "Inicio",
                subtitle = if (allTracks.isEmpty()) {
                    "Tu biblioteca local aparecerá aquí"
                } else {
                    "${allTracks.size} canciones listas para sonar"
                },
            ) {
                if (allTracks.isNotEmpty()) {
                    PremiumStatPill("${allTracks.size}")
                }
            }

            Spacer(Modifier.height(12.dp))

            TextField(
                value = "",
                onValueChange = { onNavigateToSearch() },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onNavigateToSearch),
                placeholder = { Text("Buscar canciones...", color = TextMuted) },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = AccentCoral)
                },
                readOnly = true,
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceElevated.copy(alpha = 0.72f),
                    unfocusedContainerColor = SurfaceElevated.copy(alpha = 0.72f),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(14.dp),
            )

            Spacer(Modifier.height(12.dp))

            if (isLoading) {
                PremiumLoadingState(
                    text = "Cargando biblioteca...",
                    modifier = Modifier.fillMaxSize(),
                )
                return@Column
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Reproducción rápida",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                            )
                            Text(
                            text = "Arranca sin entrar a un álbum",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                            )
                        }
                        if (allTracks.isNotEmpty()) {
                            PremiumStatPill("${allTracks.size} pistas")
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PremiumButton(
                            text = "Todo",
                            icon = Icons.Rounded.PlayArrow,
                            enabled = allTracks.isNotEmpty(),
                            onClick = { controller.playQueue(allTracks, 0) },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                        )
                        PremiumButton(
                            text = "Aleatorio",
                            icon = Icons.Rounded.Shuffle,
                            enabled = allTracks.isNotEmpty(),
                            onClick = {
                                controller.clearQueue()
                                controller.playQueue(allTracks.shuffled(), 0)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                        )
                    }
                }
            }

            if (allTracks.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                PremiumEmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = "No hay canciones locales",
                    subtitle = "Revisa permisos o sincroniza desde Michi KDE",
                )
            } else {
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Canciones recientes",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                    )
                    Text(
                        text = "20 de ${allTracks.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextDim,
                    )
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(allTracks.take(20)) { index, track ->
                        PremiumTrackItem(
                            title = track.title,
                            subtitle = "${track.artist} · ${track.album}",
                            coverId = track.coverId,
                            trailing = {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = AccentCoral,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            onClick = { controller.playQueue(allTracks, index) },
                        )
                    }
                }
            }
        }
    }
}
