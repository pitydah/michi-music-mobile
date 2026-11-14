package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * Visual-only layer for the focal and compact Mosaico cards.
 *
 * The approved crop is drawn with [ContentScale.Fit] only: the pack is a set of
 * complete artworks whose baked title, frame, ears, muzzle and instrument reach
 * the edges, so any crop (even the previous "moderate" 18% centred crop) would
 * cut a protected region. When a real asset is present the decorative geometry
 * plane is *not* painted behind it: the card's single continuous surface is the
 * matte around the image, never a second gradient surface or a footer band.
 * Only a missing/undecodable asset falls back to that plane.
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
    var decodeFailed by remember(asset?.filename) { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        if (asset == null || decodeFailed) {
            GenreVisualPlane(genreId, accent, mode = GenreViewMode.FEATURED)
        }
        if (asset != null && !decodeFailed) {
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
                    decodeFailed = true
                    GenreArtworkDiagnostics.imageFailure("MOSAIC", asset.filename,
                        result.result.throwable)
                    onImageResult?.invoke(false)
                },
            )
        }
    }
}
