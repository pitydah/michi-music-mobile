package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * Visual-only layer for FEATURED focal and secondary cards.
 *
 * The Compose plane is always painted first, so a missing, unapproved or
 * undecodable asset degrades to the pre-existing geometry instead of an empty
 * card. The approved crop is then drawn through [genreArtworkFraming]: a
 * near-matching container may take a moderate centered crop, while an
 * incompatible container keeps the whole figure and extends the card's designed
 * background (never a stretch, FillBounds, zoom or cosmetic blur). Compose still
 * owns the genre identity, the track count and the click target.
 */
@Composable
internal fun GenreMosaicArtwork(
    asset: MosaicArtworkAsset?,
    genreId: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onImageResult: ((Boolean) -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val containerRatio = genreArtworkContainerRatio(maxWidth.value, maxHeight.value)
        val framing = genreArtworkFraming(asset?.ratio, containerRatio)
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
                contentScale = if (framing == GenreArtworkFraming.MODERATE_CROP) {
                    ContentScale.Crop
                } else {
                    ContentScale.Fit
                },
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
            if (framing == GenreArtworkFraming.CONTAIN_WITH_MATTE) {
                // Documented fallback: the figure keeps its full frame and the
                // surrounding surface is an intentional matte, not a black bar.
                Box(Modifier.fillMaxSize().border(1.dp, accent.copy(alpha = 0.20f)))
            }
        }
    }
}
