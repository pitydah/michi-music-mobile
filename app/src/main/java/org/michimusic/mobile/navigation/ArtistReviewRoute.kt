package org.michimusic.mobile.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Resolves an opaque review ticket for the current composition without revoking it on
 * disposal. Navigation to an album, Now Playing, or an Activity recreation must not
 * destroy the review context; only an explicit close/back revokes it. The bounded store
 * and TTL still retire abandoned tickets.
 */
@Composable
internal fun ArtistReviewRoute(
    ticketId: String,
    root: ArtistReviewRootGuard,
    store: ArtistReviewTicketStore = ArtistReviewTickets,
    content: @Composable (ArtistReviewContext, onClose: () -> Unit) -> Unit,
    expired: @Composable (onClose: () -> Unit) -> Unit,
) {
    val review = remember(ticketId, root) {
        store.resolve(ticketId, root)
    }
    val close = { store.revoke(ticketId) }
    if (review == null) expired(close) else content(review, close)
}
