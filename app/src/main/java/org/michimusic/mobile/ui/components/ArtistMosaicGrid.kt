package org.michimusic.mobile.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
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
import org.michimusic.mobile.artist.resolveArtistArtworkConsensus
import org.michimusic.mobile.ui.motion.aeroTilePressScale
import org.michimusic.mobile.ui.theme.InterFontFamily
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion
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
    artworkRevision: Long = 0L,
    cardsMode: Boolean = false,
    selectedArtistCredit: String? = null,
    onSelectArtistCard: (String) -> Unit = {},
    libraryScopeKey: Any? = null,
) {
    val context = LocalContext.current
    val store = remember(context, selectionStore) {
        selectionStore ?: ArtistEnrichmentPersistence.from(context)
    }
    val minimumCellSize = artistMosaicMinCellWidthDp(LocalDensity.current.fontScale).dp
    val orderedArtists = remember(artists) { orderedArtistMosaicEntries(artists) }
    // Only locally verified artwork may be retained. Scope and revision invalidate all hints.
    val recentArtwork = remember(libraryId, libraryScopeKey, artworkRevision, refreshToken) {
        MichiArtworkMemory<ArtistMosaicEntry, ArtistArtworkSelection>(24)
    }
    // Reconcile artist card selection using scoped credit, never an unstable list position.
    val canonicalArtistCard = remember(orderedArtists, selectedArtistCredit) {
        michiCardReconciledKey(orderedArtists, selectedArtistCredit) { it.credit }
    }
    LaunchedEffect(cardsMode, canonicalArtistCard, selectedArtistCredit) {
        if (cardsMode && canonicalArtistCard != null && canonicalArtistCard != selectedArtistCredit) {
            onSelectArtistCard(canonicalArtistCard)
        }
    }
    // Single cache-aware tile renderer for both layouts: never fetch images over the network.
    val renderArtist: @Composable (ArtistMosaicEntry, Modifier, Boolean) -> Unit = { artist, tileModifier, isFront ->
            val albumIds = artist.albumIds
            // Structural domain identity prevents accidental cross-credit artwork reuse.
            val artworkMemoryKey = artist
            // ALL-scoped identities must be revalidated against *current* membership
            // revisions before any photo is shown: never seed them from a previous hint.
            val allowArtworkHint = michiArtworkHintAllowed(libraryId, artworkStore != null)
            // Every lazy item owns only its own state and Room request. On library/group
            // change, clear the previous portrait before reading the new scoped key.
            var portrait by remember(artist.credit, libraryId, albumIds, artist.profileScopes) {
                mutableStateOf<CommonsPortrait?>(null)
            }
            var artwork by remember(artist.credit, libraryId, albumIds, artist.profileScopes, artworkRevision) {
                mutableStateOf<ArtistArtworkSelection?>(if (allowArtworkHint) recentArtwork[artworkMemoryKey] else null)
            }
            var aggregateRevisions by remember(
                artworkStore, artist.credit, libraryId, artist.profileScopes,
            ) { mutableStateOf<List<Long>>(emptyList()) }
            LaunchedEffect(artworkStore, libraryId, artist.profileScopes) {
                aggregateRevisions = emptyList()
                if (artworkStore != null && libraryId == null) {
                    val revisionFlows = artist.profileScopes
                        .map { it.libraryId }
                        .distinct()
                        .map(artworkStore::observeLibraryRevision)
                    if (revisionFlows.isNotEmpty()) {
                        combine(revisionFlows) { revisions -> revisions.toList() }
                            .drop(1) // Initial hydration is already owned by the load effect.
                            .collect { latest ->
                                recentArtwork.remove(artworkMemoryKey)
                                aggregateRevisions = latest
                            }
                    }
                }
            }
            LaunchedEffect(store, artworkStore, artist.credit, libraryId, albumIds,
                artist.profileScopes, refreshToken, artworkRevision, aggregateRevisions) {
                // A cached portrait is only a hint: clear it before reading the scoped key so
                // a library or album-fingerprint change can never keep a stale face.
                portrait = null
                artwork = if (allowArtworkHint && aggregateRevisions.isEmpty()) recentArtwork[artworkMemoryKey] else null
                try {
                    var savedProfile: org.michimusic.mobile.artist.ArtistExternalProfile? = null
                    var savedAssociationGroupKey: String? = null
                    if (libraryId == null) {
                        val result = resolveArtistProfileConsensus(artist.profileScopes, store)
                        currentCoroutineContext().ensureActive()
                        if (artworkStore == null) {
                            // ALL: only paint a photo if EVERY participating library has the same
                            // explicitly confirmed MBID. Unassigned/ambiguous credits fail closed.
                            portrait = result.portrait
                            savedProfile = result.saved?.profile
                            savedAssociationGroupKey = result.saved?.associationGroupKey
                        } else {
                            artwork = resolveArtistArtworkConsensus(result, artworkStore)
                            currentCoroutineContext().ensureActive()
                        }
                    } else if (artworkStore == null) {
                        if (scopedArtistCreditKey(libraryId, artist.credit, albumIds) != null) {
                            val saved = store.load(libraryId, artist.credit, albumIds)
                            currentCoroutineContext().ensureActive()
                            portrait = saved?.profile?.portrait?.takeIf(::allowedCommonsPortrait)
                            savedProfile = saved?.profile
                            savedAssociationGroupKey = saved?.associationGroupKey
                        }
                    } else if (scopedArtistCreditKey(libraryId, artist.credit, albumIds) != null) {
                        // Production mosaic never lets a legacy portrait URL reach Coil:
                        // only the persisted, decoded artwork file may paint a tile. Recovery
                        // lives in Enriquecer, not in a network request while scrolling.
                        val saved = store.load(libraryId, artist.credit, albumIds)
                        currentCoroutineContext().ensureActive()
                        savedProfile = saved?.profile
                        savedAssociationGroupKey = saved?.associationGroupKey
                    }
                    if (artworkStore != null && libraryId != null) {
                        val anchor = artistIdentityAnchor(
                            libraryId, artist.credit, albumIds, savedProfile,
                            savedAssociationGroupKey,
                        )
                        if (anchor != null) {
                            artwork = artworkStore.load(anchor).firstOrNull {
                                it.slot == ArtworkSlot.ARTIST_THUMB &&
                                    it.status == ArtworkLoadStatus.READY &&
                                    it.confirmedMbid == anchor.confirmedMbid
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    val readyArtwork = artwork?.takeIf {
                        it.status == ArtworkLoadStatus.READY && it.localImagePath?.isNotBlank() == true
                    }
                    if (allowArtworkHint && readyArtwork != null) {
                        recentArtwork.put(artworkMemoryKey, readyArtwork)
                    } else recentArtwork.remove(artworkMemoryKey)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A failed cache read is a missing photo, never an invented identity.
                    // Retrying lives in the enrichment surface, not under the portrait.
                    recentArtwork.remove(artworkMemoryKey)
                    portrait = null
                    artwork = null
                }
            }
            ArtistMosaicTile(
                artist = artist,
                portrait = portrait,
                artwork = artwork,
                onClick = { onOpenArtist(artist.credit) },
                modifier = tileModifier,
                maxPortraitDiameter = if (cardsMode) 210.dp else 144.dp,
                enabled = isFront,
                showLabel = isFront,
                showSupportingMetadata = cardsMode && isFront,
                highlightPortrait = cardsMode && isFront,
            )
    }
    if (cardsMode) {
        key(libraryScopeKey) { MichiCardDeck(
            items = orderedArtists,
            itemKey = { it.credit },
            itemLabel = { artist -> artist.credit.ifBlank { "Artista desconocido" } },
            selectedKey = canonicalArtistCard,
            onSelect = onSelectArtistCard,
            modifier = modifier.fillMaxSize().testTag("artist_cards_deck"),
            onOpenSelected = { artist -> onOpenArtist(artist.credit) },
            circularArtwork = true,
            navigationButtons = false,
        ) { artist, isFront ->
            // Preserve Michi's round portrait, not a rectangular card frame.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                renderArtist(artist, Modifier.padding(16.dp), isFront)
            }
        } }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = minimumCellSize),
            modifier = modifier.fillMaxSize().testTag("artist_mosaic_grid"),
            contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(items = orderedArtists, key = { it.credit }) { artist ->
                renderArtist(artist, if (LocalVisualPreferences.current.reducedMotion) Modifier else Modifier.animateItem(), true)
            }
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
    maxPortraitDiameter: Dp = 144.dp,
    enabled: Boolean = true,
    showLabel: Boolean = true,
    showSupportingMetadata: Boolean = false,
    highlightPortrait: Boolean = false,
) {
    val name = artist.credit.ifBlank { "Artista desconocido" }
    val localArtwork = artwork?.localImagePath
        ?.takeIf { artwork.status == ArtworkLoadStatus.READY && it.isNotBlank() }
    // Profile URLs remain attribution/provenance only. A production mosaic portrait
    // is rendered exclusively from the verified private artwork selection.
    val model: Any? = localArtwork?.let(::File)
    val albumDescription = if (artist.albumCount == 1) "1 álbum" else "${artist.albumCount} álbumes"
    val trackDescription = if (artist.trackCount == 1) "1 canción" else "${artist.trackCount} canciones"
    // One accessible target per artist: the whole tile opens the ficha. Cache retries and
    // identity review belong to the enrichment surface, never to a second CTA under a face.
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reducedMotion = LocalVisualPreferences.current.reducedMotion
    val scale by animateFloatAsState(
        targetValue = aeroTilePressScale(pressed, reducedMotion),
        animationSpec = if (reducedMotion) snap() else tween(
            durationMillis = MichiMotion.durationFast,
            easing = MichiMotion.easingDecelerate,
        ),
        label = "aero_artist_tile_press",
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                role = Role.Button,
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
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
            val diameter = maxWidth.coerceAtMost(maxPortraitDiameter)
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
                        width = if (LocalVisualPreferences.current.highContrast) 2.dp
                        else if (highlightPortrait) 1.4.dp else 0.9.dp,
                        // ALL layers stay discernible when high-contrast visuals disable rotation.
                        color = if (LocalVisualPreferences.current.highContrast) Color.White
                        else if (highlightPortrait) PrimaryPink.copy(alpha = 0.56f)
                        else if (model != null) Color.White.copy(alpha = 0.23f)
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
        if (showLabel) {
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
            if (showSupportingMetadata) {
                Text(
                    text = "$albumDescription · $trackDescription",
                    modifier = Modifier.fillMaxWidth(),
                    color = TextSecondary,
                    fontFamily = InterFontFamily,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // Preserve a baseline for rear circles without exposing ghosted artist labels.
            Box(Modifier.fillMaxWidth().height(38.dp))
        }
    }
}
