package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * Visual-only optional layer. Installed production assets must have no baked
 * names/frame and full-subject safe composition; missing files render transparent so the
 * pre-existing Compose geometry remains visible. Never called by FEATURED.
 */
@Composable
internal fun GenreEditorialArtwork(
    genreTitle: String,
    treatment: GenreEditorialTreatment,
    columns: Int,
    availableAssetNames: Set<String>,
    modifier: Modifier = Modifier,
) {
    val file = remember(genreTitle, treatment, columns) {
        editorialProductionAssetPath(genreTitle, treatment, columns)
    } ?: return
    // Avoid failed requests when approved production images are not bundled.
    if (file.substringAfterLast('/') !in availableAssetNames) return
    val context = LocalContext.current
    val request = remember(context, file) {
        ImageRequest.Builder(context)
            .data("file:///android_asset/$file")
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
    )
}
