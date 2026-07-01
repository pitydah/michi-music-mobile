package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import org.koin.androidx.compose.koinViewModel
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.GlassCardVariant
import org.michimusic.mobile.ui.components.MichiEmptyState
import org.michimusic.mobile.ui.components.MichiLoadingState
import org.michimusic.mobile.ui.components.MichiSectionHeader
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.rememberAudioController

@Composable
fun HomeScreen(
    onNavigateToSearch: () -> Unit = {},
) {
    val viewModel: AlbumsViewModel = koinViewModel(
        viewModelStoreOwner = LocalContext.current as ComponentActivity,
    )
    val allTracks by viewModel.allTracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val controller = rememberAudioController()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MichiSpacing.lg),
    ) {
        Column {
            Spacer(Modifier.height(MichiSpacing.lg))
            Text("Michi Music", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
            Text("${allTracks.size} canciones locales", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            Spacer(Modifier.height(MichiSpacing.lg))

            OutlinedTextField(
                value = "",
                onValueChange = { onNavigateToSearch() },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Buscar canciones...", color = TextMuted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted) },
                readOnly = true,
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentPink,
                ),
            )

            Spacer(Modifier.height(MichiSpacing.lg))

            if (isLoading) {
                MichiLoadingState(text = "Cargando música local...")
                return@Column
            }

            if (allTracks.isEmpty()) {
                MichiEmptyState(
                    icon = Icons.Default.MusicNote,
                    title = "No se encontraron canciones",
                    description = "Asegúrate de tener música en el dispositivo y haber concedido permisos",
                )
                return@Column
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                MichiSectionHeader(title = "Reproducción rápida")
                Spacer(Modifier.height(MichiSpacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MichiSpacing.md),
                ) {
                    GlassCard(
                        modifier = Modifier.weight(1f).clickable { controller.playQueue(allTracks, 0) },
                        variant = GlassCardVariant.COMPACT,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.PlayArrow, null, tint = AccentPink, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.height(MichiSpacing.xs))
                            Text("Reproducir todo", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        }
                    }
                    GlassCard(
                        modifier = Modifier.weight(1f).clickable {
                            controller.clearQueue()
                            controller.playQueue(allTracks.shuffled(), 0)
                        },
                        variant = GlassCardVariant.COMPACT,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Shuffle, null, tint = AccentCoral, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.height(MichiSpacing.xs))
                            Text("Aleatorio", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        }
                    }
                }
            }

            Spacer(Modifier.height(MichiSpacing.lg))
            MichiSectionHeader(title = "Todas las canciones", subtitle = "${allTracks.size} canciones")
            Spacer(Modifier.height(MichiSpacing.sm))

            allTracks.take(8).forEachIndexed { index, track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { controller.playQueue(allTracks, index) }
                        .padding(vertical = MichiSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceElevated),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.MusicNote, null, tint = TextMuted, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(MichiSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${track.artist} · ${track.album}", style = MaterialTheme.typography.bodySmall, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Default.PlayArrow, null, tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(MichiSpacing.xxl))
        }
    }
}
