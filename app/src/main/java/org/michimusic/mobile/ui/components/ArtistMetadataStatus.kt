package org.michimusic.mobile.ui.components

import org.michimusic.mobile.artist.ArtistMetadataOutcome

/**
 * Truthful, per-stage copy for optional provider data. A provider/network
 * failure must never read as "this artist has no biography" or "no photo".
 */
internal fun biographyStatusText(outcome: ArtistMetadataOutcome?): String = when (outcome) {
    ArtistMetadataOutcome.HTTP_ERROR,
    ArtistMetadataOutcome.TIMEOUT,
    ArtistMetadataOutcome.NETWORK_ERROR,
    -> "No fue posible consultar la biografía. Se conserva la información local y puedes reintentarlo."
    ArtistMetadataOutcome.DECODE_ERROR,
    ArtistMetadataOutcome.CACHE_ERROR,
    -> "La respuesta del proveedor no permitió verificar la biografía."
    else -> "No hay biografía verificable disponible para este artista."
}

internal fun portraitStatusText(outcome: ArtistMetadataOutcome?): String = when (outcome) {
    ArtistMetadataOutcome.NO_P18,
    ArtistMetadataOutcome.NO_ALLOWED_LICENSE,
    ArtistMetadataOutcome.BAD_ATTRIBUTION,
    ArtistMetadataOutcome.NO_SITELINK,
    ArtistMetadataOutcome.NO_TEXT,
    ArtistMetadataOutcome.NO_IDENTITY,
    -> "Sin fotografías verificables."
    ArtistMetadataOutcome.HTTP_ERROR,
    ArtistMetadataOutcome.TIMEOUT,
    ArtistMetadataOutcome.NETWORK_ERROR,
    -> "No fue posible recuperar fotografías; se mantiene el monograma."
    ArtistMetadataOutcome.DECODE_ERROR,
    ArtistMetadataOutcome.CACHE_ERROR,
    -> "Las fotografías no pudieron verificarse; se mantiene el monograma."
    else -> "Sin fotografías verificables."
}
