package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.michimusic.mobile.artist.ArtistEnrichmentPersistence
import org.michimusic.mobile.artist.ArtistEnrichmentSelectionStore
import org.michimusic.mobile.artist.CommonsPortrait
import org.michimusic.mobile.artist.allowedCommonsPortrait
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.artistIdentityAnchor
import org.michimusic.mobile.artist.scopedArtistCreditKey
import org.michimusic.mobile.artist.resolveArtistProfileConsensus
import org.michimusic.mobile.ui.theme.InterFontFamily
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * Aero artist gallery: responsive portrait circles, no per-artist card frames.
 * Only confirmed, licensed Artist Enrichment cache entries are eligible for a photo.
 * Scrolling does not trigger MusicBrainz/Wikimedia lookups, guesses or tag scans.
 */
@Composable
fun ArtistMosaicGrid(
    artists: List<ArtistMosaicEntry>,
    libraryId: String?,
    refreshToken: Int,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectionStore: ArtistEnrichmentSelectionStore? = null,
    artworkStore: ArtistArtworkStore? = null,
) {
    val context = LocalContext.current
    val store = remember(context, selectionStore) {
        selectionStore ?: ArtistEnrichmentPersistence.from(context)
    }
    val minimumCellSize = artistMosaicMinCellWidthDp(LocalDensity.current.fontScale).dp
    val orderedArtists = remember(artists) { orderedArtistMosaicEntries(artists) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = minimumCellSize),
        modifier = modifier.fillMaxSize().testTag("artist_mosaic_grid"),
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(items = orderedArtists, key = { it.credit }) { artist ->
            val albumIds = artist.albumIds
            // Every lazy item owns only its own state and Room request. On library/group
            // change, clear the previous portrait before reading the new scoped key.
            var portrait by remember(artist.credit, libraryId, albumIds, artist.profileScopes) {
                mutableStateOf<CommonsPortrait?>(null)
            }
            var artwork by remember(artist.credit, libraryId, albumIds, artist.profileScopes) {
                mutableStateOf<ArtistArtworkSelection?>(null)
            }
            LaunchedEffect(store, artworkStore, artist.credit, libraryId, albumIds,
                artist.profileScopes, refreshToken) {
                // A cached portrait is only a hint: clear it before reading the scoped key so
                // a library or album-fingerprint change can never keep a stale face.
                portrait = null
                artwork = null
                try {
                    var savedProfile: org.michimusic.mobile.artist.ArtistExternalProfile? = null
                    if (libraryId == null) {
                        // ALL: only paint a photo if EVERY participating library has the same
                        // explicitly confirmed MBID. Unassigned/ambiguous credits fail closed.
                        val result = resolveArtistProfileConsensus(artist.profileScopes, store)
                        currentCoroutineContext().ensureActive()
                        portrait = result.portrait
                        savedProfile = result.saved?.profile
                    } else if (scopedArtistCreditKey(libraryId, artist.credit, albumIds) != null) {
                        val saved = store.load(libraryId, artist.credit, albumIds)
                        currentCoroutineContext().ensureActive()
                        portrait = saved?.profile?.portrait?.takeIf(::allowedCommonsPortrait)
                        savedProfile = saved?.profile
                    }
                    if (artworkStore != null && libraryId != null) {
                        val anchor = artistIdentityAnchor(
                            libraryId, artist.credit, albumIds, savedProfile,
                        )
                        if (anchor != null) {
                            artwork = artworkStore.load(anchor).firstOrNull {
                                it.slot == ArtworkSlot.ARTIST_THUMB &&
                                    it.status == ArtworkLoadStatus.READY &&
                                    it.confirmedMbid == anchor.confirmedMbid
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed cache read is a missing photo, never an invented identity.
                    // Retrying lives in the enrichment surface, not under the portrait.
                    portrait = null
                    artwork = null
                }
            }
            ArtistMosaicTile(
                artist = artist,
                portrait = portrait,
                artwork = artwork,
                onClick = { onOpenArtist(artist.credit) },
            )
        }
    }
}

@Composable
internal fun ArtistMosaicTile(
    artist: ArtistMosaicEntry,
    portrait: CommonsPortrait?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: ArtistArtworkSelection? = null,
) {
    val name = artist.credit.ifBlank { "Artista desconocido" }
    val localArtwork = artwork?.localImagePath
        ?.takeIf { artwork.status == ArtworkLoadStatus.READY && it.isNotBlank() }
    val model: Any? = localArtwork?.let(::File) ?: portrait?.imageUrl
    val albumDescription = if (artist.albumCount == 1) "1 álbum" else "${artist.albumCount} álbumes"
    val trackDescription = if (artist.trackCount == 1) "1 canción" else "${artist.trackCount} canciones"
    // One accessible target per artist: the whole tile opens the ficha. Cache retries and
    // identity review belong to the enrichment surface, never to a second CTA under a face.
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "Abrir artista $name. $albumDescription y $trackDescription"
            }
            .testTag("artist_mosaic_tile_${artist.credit}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val diameter = maxWidth.coerceAtMost(144.dp)
            Box(
                modifier = Modifier
                    .size(diameter)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF515068), Color(0xFF29293D)),
                        ),
                    )
                    .border(
                        width = 0.9.dp,
                        color = if (model != null) Color.White.copy(alpha = 0.23f)
                        else PrimaryPink.copy(alpha = 0.37f),
                        shape = CircleShape,
                    )
                    .testTag("artist_mosaic_portrait_${artist.credit}"),
                contentAlignment = Alignment.Center,
            ) {
                var photoVisible by remember(model) { mutableStateOf(false) }
                // Kept under the async image, including offline/error states; never
                // replace a failed portrait with unrelated album artwork.
                if (!photoVisible) {
                    Text(
                        text = artistMosaicMonogram(name),
                        modifier = Modifier.testTag("artist_mosaic_monogram_${artist.credit}"),
                        fontFamily = InterFontFamily,
                        fontSize = 31.sp,
                        fontWeight = FontWeight.Medium,
                        color = PureWhite.copy(alpha = 0.92f),
                    )
                }
                if (model != null) {
                    val alignment = artwork?.focus?.let {
                        BiasAlignment(it.x * 2f - 1f, it.y * 2f - 1f)
                    } ?: Alignment.Center
                    AsyncImage(
                        model = model,
                        contentDescription = null, // Parent owns the accessible artist action.
                        contentScale = ContentScale.Crop,
                        alignment = alignment,
                        onSuccess = { photoVisible = true },
                        onError = { photoVisible = false },
                        modifier = Modifier.matchParentSize()
                            .clip(CircleShape)
                            .testTag("artist_mosaic_photo_${artist.credit}"),
                    )
                    if (photoVisible) {
                        Box(
                            Modifier.matchParentSize()
                                .testTag("artist_mosaic_photo_loaded_${artist.credit}"),
                        )
                    }
                }
            }
        }
        Text(
            text = name,
            modifier = Modifier.fillMaxWidth(),
            color = PureWhite,
            fontFamily = InterFontFamily,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
