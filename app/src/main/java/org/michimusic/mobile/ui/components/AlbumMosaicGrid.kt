package org.michimusic.mobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import org.michimusic.core.models.LibraryScope
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum
import org.michimusic.mobile.R
import org.michimusic.mobile.ui.screens.albumNavigationIdentity
import org.michimusic.mobile.ui.screens.albumNavigationIdentityCounts
import org.michimusic.mobile.ui.motion.albumMosaicMotionKey
import org.michimusic.mobile.ui.motion.albumMosaicPressScale
import org.michimusic.mobile.ui.motion.aeroSharedAlbumArtwork
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

/** Album identity, artwork and count all come from the same local MediaStore group. */
@Composable
fun AlbumMosaicGrid(
    albums: List<LocalAlbum>,
    scope: LibraryScope,
    onOpenAlbum: (String) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
) {
    val identityCounts = remember(albums, scope) { albumNavigationIdentityCounts(albums, scope) }
    val reducedMotion = LocalVisualPreferences.current.reducedMotion || LocalVisualPreferences.current.highContrast
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 132.dp),
        state = state,
        modifier = modifier.testTag("album_mosaic_grid"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        itemsIndexed(
            items = albums,
            key = { index, group ->
                val identity = albumNavigationIdentity(group, scope)
                albumMosaicMotionKey(identity, identityCounts[identity] ?: 0, index)
            },
        ) { _, group ->
            val album = group.album
            val identity = albumNavigationIdentity(group, scope)
            val navigable = identityCounts[identity] == 1
            val interactions = remember(identity, navigable) { MutableInteractionSource() }
            val pressed by interactions.collectIsPressedAsState()
            val pressScale by animateFloatAsState(
                targetValue = albumMosaicPressScale(pressed, navigable, reducedMotion),
                animationSpec = if (reducedMotion) snap() else tween(
                    durationMillis = MichiMotion.durationFast,
                    easing = MichiMotion.easingDecelerate,
                ),
                label = "aero_album_mosaic_press",
            )
            Column(
                modifier = Modifier
                    .then(if (reducedMotion || !navigable) Modifier else Modifier.animateItem())
                    .fillMaxWidth()
                    .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        enabled = navigable,
                        interactionSource = interactions,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Abrir álbum ${album.title}",
                    ) { onOpenAlbum(identity) }
                    .testTag("album_tile_$identity"),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                AlbumArtView(
                    coverStyle = coverStyleFor(album.coverId.ifBlank { album.title }),
                    imageModel = album.coverId,
                    fallbackMediaUri = group.tracks.firstOrNull()?.filepath,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        .aeroSharedAlbumArtwork(identity, navigable, reducedMotion),
                    cornerRadius = 12.dp,
                )
                Text(
                    text = album.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureWhite,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 7.dp),
                )
                Text(
                    text = album.year.takeIf { it > 0 }?.toString() ?: "Año desconocido",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 1,
                )
                Text(
                    text = album.artist,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp,
                )
                if (!navigable) {
                    Text("Álbum duplicado; comprueba la biblioteca", color = TextMuted, fontSize = 11.sp)
                }
                Text(
                    text = pluralStringResource(R.plurals.track_count, group.tracks.size, group.tracks.size),
                    fontSize = 11.sp,
                    color = TextMuted,
                    maxLines = 1,
                    modifier = Modifier.testTag("album_tile_track_count"),
                )
            }
        }
    }
}
