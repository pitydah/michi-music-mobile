package org.michimusic.mobile.artist.artwork

import org.michimusic.data.artist.ArtistArtworkAutoOutboxRecord

/** Requires a nonempty set of outbox rows ALREADY validated against the exact
 * Room album-credit binding by the worker. This is not the profile-wide
 * needsConfirmation flag: other visible albums may still be unbound.
 */
internal object ArtistArtworkAutoContextPolicy {
    fun needsConfirmation(
        libraryId: String, creditKey: String, associationId: String, sourceId: String,
        confirmedRows: Collection<ArtistArtworkAutoOutboxRecord>,
    ): Boolean = confirmedRows.isEmpty() || confirmedRows.any { row ->
        row.libraryId != libraryId || row.creditKey != creditKey ||
            row.associationId != associationId || row.sourceId != sourceId ||
            row.albumKey.isBlank() || row.state != "PENDING"
    }
}
