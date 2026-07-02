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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.mobile.screens.SearchResult
import org.michimusic.mobile.screens.SearchViewModel
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.AudioController

@Composable
fun SearchScreen(
    viewModel: SearchViewModel = koinViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val audioController: AudioController = koinInject()

    LaunchedEffect(Unit) {
        viewModel.loadLocalTracks()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Buscar",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
        )
        Text(
            text = "Encuentra música local y sincronizada",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Canciones, artistas, álbumes...", color = TextMuted) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = AccentCoral)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearSearch) {
                        Icon(Icons.Default.Clear, contentDescription = "Limpiar", tint = TextSecondary)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCoral,
                unfocusedBorderColor = SurfaceElevated.copy(alpha = 0.72f),
                focusedContainerColor = SurfaceElevated.copy(alpha = 0.42f),
                unfocusedContainerColor = SurfaceElevated.copy(alpha = 0.42f),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentCoral,
            ),
        )

        Spacer(Modifier.height(12.dp))

        when {
            isSearching -> LoadingState()
            query.length >= 2 && results.isEmpty() -> EmptySearchState("Sin resultados")
            results.isNotEmpty() -> {
                SearchSummary(
                    total = results.size,
                    local = results.count { it.source == "Local" },
                    synced = results.count { it.source == "Sincronizada" },
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(results) { result ->
                        SearchResultRow(
                            result = result,
                            onPlay = {
                                val allTracks = results.map { it.track }
                                val idx = allTracks.indexOfFirst { it.id == result.track.id }
                                if (idx >= 0) {
                                    audioController.playQueue(allTracks, idx)
                                }
                            },
                        )
                    }
                }
            }
            else -> EmptySearchState("Busca en tu biblioteca local y sincronizada", large = true)
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = AccentCoral)
    }
}

@Composable
private fun EmptySearchState(
    text: String,
    large: Boolean = false,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(if (large) 64.dp else 48.dp),
                tint = TextDim,
            )
            Spacer(Modifier.height(if (large) 16.dp else 12.dp))
            Text(
                text,
                style = if (large) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = if (large) TextMuted else TextDim,
            )
        }
    }
}

@Composable
private fun SearchSummary(
    total: Int,
    local: Int,
    synced: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchChip(text = "$total resultados", highlighted = true)
        SearchChip(text = "$local local")
        SearchChip(text = "$synced sync")
    }
}

@Composable
private fun SearchChip(
    text: String,
    highlighted: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (highlighted) AccentCoral.copy(alpha = 0.18f) else SurfaceElevated.copy(alpha = 0.58f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) AccentCoral else TextMuted,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SearchResultRow(
    result: SearchResult,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceElevated.copy(alpha = 0.48f))
            .clickable(onClick = onPlay)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchArtwork(coverId = result.track.coverId)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = result.track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${result.track.artist} · ${result.track.album}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = result.source,
            style = MaterialTheme.typography.labelSmall,
            color = if (result.source == "Sincronizada") AccentCoral else TextDim,
        )
    }
}

@Composable
private fun SearchArtwork(coverId: String) {
    val modifier = Modifier
        .size(42.dp)
        .clip(RoundedCornerShape(8.dp))

    if (coverId.isNotEmpty()) {
        AsyncImage(
            model = "content://media/external/audio/albumart/$coverId",
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.background(
                Brush.verticalGradient(
                    listOf(
                        AccentCoral.copy(alpha = 0.28f),
                        AccentPink.copy(alpha = 0.16f),
                    )
                )
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = AccentCoral,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
