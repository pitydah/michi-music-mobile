package org.michimusic.core.metadata

/**
 * Small built-in presets inspired by tag editors' batch tools. They are proposals,
 * not automatic library processing; editing and external fetching remain opt-in.
 */
data class MetadataRecipe(
    val id: String,
    val title: String,
    val actions: List<MetadataBatchAction>,
    /** Destructive presets must trigger a separate explicit confirmation in the host UI. */
    val destructive: Boolean = false,
)

object MetadataRecipeCatalog {
    val trimCommonFields = MetadataRecipe(
        "trim_common", "Recortar espacios de títulos y créditos", listOf(
            MetadataBatchAction.Trim(MetadataField.TITLE),
            MetadataBatchAction.Trim(MetadataField.ARTIST),
            MetadataBatchAction.Trim(MetadataField.ALBUM),
            MetadataBatchAction.Trim(MetadataField.ALBUM_ARTIST),
            MetadataBatchAction.Trim(MetadataField.COMPOSER),
        ))

    val fillMissingAlbumArtist = MetadataRecipe(
        "fill_album_artist", "Completar artista del álbum ausente", listOf(
            MetadataBatchAction.Copy(MetadataField.ARTIST, MetadataField.ALBUM_ARTIST,
                onlyIfMissing = true)))

    val clearComments = MetadataRecipe(
        "clear_comment", "Borrar comentarios seleccionados", listOf(
            MetadataBatchAction.Set(MetadataChange.Clear(MetadataField.COMMENT))),
        destructive = true)

    val all = listOf(trimCommonFields, fillMissingAlbumArtist, clearComments)
}
