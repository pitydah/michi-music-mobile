package org.michimusic.mobile.ui.coverflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import org.michimusic.mobile.ui.theme.PureWhite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.michimusic.mobile.library.coverflow.CoverFlowAlbum
import org.michimusic.mobile.ui.components.aeroCoverFlowShouldAnimate
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.LocalVisualPreferences

/**
 * Native Compose CoverFlow. No RecyclerView/AndroidView bridge; no third-party runtime.
 * Center selection is separate from playback: tapping a side cover selects it,
 * tapping the centered cover invokes the caller's playback action.
 */
@Composable
fun MichiCoverFlowHost(
    albums: List<CoverFlowAlbum>,
    onCurrentChanged: (Int) -> Unit = {},
    onAlbumClick: (Int) -> Unit = {},
    initialPage: Int = 0,
    selectedAlbumId: String? = null,
    onAlbumSelected: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) return
    val initialTargetPage = if (selectedAlbumId != null) {
        val found = albums.indexOfFirst { it.id == selectedAlbumId }
        if (found >= 0) found else initialPage
    } else initialPage

    val state = rememberPagerState(
        initialPage = safeCoverFlowIndex(initialTargetPage, albums.size),
        pageCount = { albums.size },
    )
    val scope = rememberCoroutineScope()
    val reducedMotion = LocalVisualPreferences.current.reducedMotion
    val density = LocalDensity.current.density

    // Clamping when album list shrinks
    LaunchedEffect(albums.size) {
        if (albums.isNotEmpty() && state.currentPage > albums.lastIndex) {
            val safeIdx = albums.lastIndex
            state.scrollToPage(safeIdx)
            onCurrentChanged(safeIdx)
            onAlbumSelected?.invoke(albums[safeIdx].id)
        }
    }

    // Programmatic target selection and deleted album recovery (K46D-48)
    LaunchedEffect(selectedAlbumId, albums) {
        if (selectedAlbumId != null) {
            val targetIdx = albums.indexOfFirst { it.id == selectedAlbumId }
            if (targetIdx in albums.indices) {
                if (targetIdx != state.currentPage) {
                    if (aeroCoverFlowShouldAnimate(reducedMotion)) {
                        state.animateScrollToPage(targetIdx)
                    } else {
                        state.scrollToPage(targetIdx)
                    }
                }
            } else if (albums.isNotEmpty()) {
                // K46D-48: Target album was deleted or filtered out; fallback gracefully to current safe index.
                val safeIdx = state.currentPage.coerceIn(0, albums.lastIndex)
                if (safeIdx != state.currentPage) {
                    state.scrollToPage(safeIdx)
                }
                onAlbumSelected?.invoke(albums[safeIdx].id)
            }
        }
    }

    // Settled-only synchronization: prevents oscillation and feedback loops during animations (K46D-48)
    LaunchedEffect(state, albums) {
        snapshotFlow {
            if (!state.isScrollInProgress) state.settledPage else null
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { settledPage ->
                if (settledPage in albums.indices) {
                    onCurrentChanged(settledPage)
                    val settledAlbumId = albums[settledPage].id
                    if (settledAlbumId != selectedAlbumId) {
                        onAlbumSelected?.invoke(settledAlbumId)
                    }
                }
            }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Floor ambient reflection
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.72f)
                .height(14.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(PureWhite.copy(alpha = 0.05f), Color.Transparent),
                    ),
                ),
        )

        // Constrain the card to the actual viewport: very small windows must not clip covers.
        val pageWidth = minOf(maxWidth, if (maxWidth < 350.dp) 184.dp else 212.dp)
        val sidePadding = ((maxWidth - pageWidth) / 2).coerceAtLeast(0.dp)
        HorizontalPager(
            state = state,
            modifier = Modifier.fillMaxSize().testTag("michi_coverflow_compose"),
            pageSize = PageSize.Fixed(pageWidth),
            contentPadding = PaddingValues(horizontal = sidePadding),
            pageSpacing = 8.dp,
            key = { albums[it].id },
        ) { page ->
            val album = albums[page]
            val signedOffset = (state.currentPage - page) + state.currentPageOffsetFraction
            val visual = coverFlowGeometry(signedOffset, reducedMotion)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .graphicsLayer {
                        rotationY = visual.rotationY
                        cameraDistance = 24f * density
                        scaleX = visual.scale
                        scaleY = visual.scale
                        alpha = visual.opacity
                        shadowElevation = if (visual.raised) 8f else 0f
                    }
                    .clip(RoundedCornerShape(16.dp))
                    .then(
                        if (visual.raised) {
                            Modifier.border(0.65.dp, AeroHairline, RoundedCornerShape(16.dp))
                        } else Modifier
                    )
                    .clickable {
                        if (page == state.currentPage) {
                            onAlbumClick(page)
                        } else {
                            scope.launch {
                                if (aeroCoverFlowShouldAnimate(reducedMotion)) {
                                    state.animateScrollToPage(page)
                                } else {
                                    state.scrollToPage(page)
                                }
                            }
                        }
                    }
                    .semantics(mergeDescendants = true) {
                        selected = page == state.currentPage
                        contentDescription = "${album.title}, ${album.artist}. " +
                            (if (page == state.currentPage) "Toca para reproducir" else "Toca para seleccionar")
                    }
                    .testTag("coverflow_album_${album.id}"),
                contentAlignment = Alignment.Center,
            ) {
                AlbumArtView(
                    coverStyle = coverStyleFor(album.coverUri.ifBlank { album.id }),
                    imageModel = album.coverUri,
                    fallbackMediaUri = album.sourceAudioUri,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    cornerRadius = 16.dp,
                )
            }
        }
    }
}
