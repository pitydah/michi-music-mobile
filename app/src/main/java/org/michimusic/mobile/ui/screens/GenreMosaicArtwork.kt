package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * Visual-only layer for the focal and compact Mosaico cards.
 *
 * The Compose plane is painted first, so a missing, unapproved or undecodable
 * asset degrades to the card's own surface instead of an empty card. The
 * approved crop is then drawn with [ContentScale.Fit] only: the pack is a set of
 * complete artworks whose baked title, frame, ears, muzzle and instrument reach
 * the edges, so any crop (even the previous "moderate" 18% centred crop) would
 * cut a protected region. The leftover area is the card's continuous surface,
 * never a stretched/zoomed/blurred bitmap and never a FillBounds.
 *
 * Compose still owns the genre identity, the track count and the click target;
 * the image itself is decorative and exposes no content description.
 */
@Composable
internal fun GenreMosaicArtwork(
    asset: MosaicArtworkAsset?,
    genreId: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onImageResult: ((Boolean) -> Unit)? = null,
) {
    Box(modifier.fillMaxSize()) {
        GenreVisualPlane(genreId, accent, mode = GenreViewMode.FEATURED)
        if (asset != null) {
            val context = LocalContext.current
            val request = remember(context, asset.filename) {
                ImageRequest.Builder(context)
                    .data("file:///android_asset/$mosaicFolder/${asset.filename}")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null, // The parent card supplies the real scoped name/count.
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onSuccess = {
                    GenreArtworkDiagnostics.imageSuccess("MOSAIC", asset.filename)
                    onImageResult?.invoke(true)
                },
                onError = { result ->
                    GenreArtworkDiagnostics.imageFailure("MOSAIC", asset.filename,
                        result.result.throwable)
                    onImageResult?.invoke(false)
                },
            )
        }
    }
}
