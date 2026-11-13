package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/** The existing WebP is a complete poster: do not paint another title or frame. */
@Composable
internal fun GenreEditorialPosterArtwork(
    poster: EditorialPosterAsset,
    modifier: Modifier = Modifier,
    onImageResult: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val request = remember(context, poster.filename) {
        ImageRequest.Builder(context)
            .data("file:///android_asset/$editorialPosterFolder/${poster.filename}")
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null, // Parent tile supplies the real scoped name/count.
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
        onSuccess = {
            GenreArtworkDiagnostics.imageSuccess("EDITORIAL", poster.filename)
            onImageResult?.invoke(true)
        },
        onError = { result ->
            GenreArtworkDiagnostics.imageFailure("EDITORIAL", poster.filename,
                result.result.throwable)
            onImageResult?.invoke(false)
        },
    )
}
