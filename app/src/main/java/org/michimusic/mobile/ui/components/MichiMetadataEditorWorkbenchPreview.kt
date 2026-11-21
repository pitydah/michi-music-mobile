package org.michimusic.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.mobile.ui.theme.AeroTheme

/** IDE-only sample. No URI access, network traffic, file write or image generation. */
private val metadataPreviewSnapshot = MetadataSnapshot(
    target = MetadataTarget("", "preview", "content://preview/only", "preview-in-memory"),
    tagsVerified = true,
    known = mapOf(
        MetadataField.TITLE to listOf("Noches de jazz"),
        MetadataField.ARTIST to listOf("Gato Azul"),
        MetadataField.ALBUM to listOf("Música para escuchar"),
        MetadataField.ALBUM_ARTIST to listOf("Gato Azul"),
        MetadataField.GENRE to listOf("Jazz; Fusion"),
        MetadataField.TRACK_NUMBER to listOf("03"),
    ),
    verifiedFields = setOf(
        MetadataField.TITLE, MetadataField.ARTIST, MetadataField.ALBUM,
        MetadataField.ALBUM_ARTIST, MetadataField.GENRE,
        MetadataField.TRACK_NUMBER, MetadataField.COMPOSER,
    ),
)

@Preview(name = "ME-10 · 360 dp", widthDp = 360, heightDp = 780,
    showBackground = true, backgroundColor = 0xFF090C12)
@Composable
private fun MetadataWorkbench360Preview() {
    AeroTheme { MichiMetadataEditorPreviewContent(metadataPreviewSnapshot, onDismiss = {}) }
}

@Preview(name = "ME-10 · 411 dp", widthDp = 411, heightDp = 850,
    showBackground = true, backgroundColor = 0xFF090C12)
@Composable
private fun MetadataWorkbench411Preview() {
    AeroTheme { MichiMetadataEditorPreviewContent(metadataPreviewSnapshot, onDismiss = {}) }
}
