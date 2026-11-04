package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Precedence contract for the artist detail collection, from plan M2. These are the
 * five real states the UI branches on; the patch referenced them without shipping the
 * policy, so this suite is the missing safety net for Q07, Q08 and Q09.
 */
class ArtistDetailRecoveryPolicyTest {

    @Test
    fun roomErrorWinsEvenWhenAlbumsAreCached() {
        assertEquals(
            ArtistDetailCollectionPhase.SOURCE_ERROR,
            artistDetailCollectionPhase(
                scopeReady = true,
                scopeError = "Room unavailable",
                mediaLoading = false,
                mediaError = null,
                hasAlbums = true,
            ),
        )
    }

    @Test
    fun scopeErrorWinsOverIncompleteScope() {
        assertEquals(
            ArtistDetailCollectionPhase.SOURCE_ERROR,
            artistDetailCollectionPhase(
                scopeReady = false,
                scopeError = "membership read failed",
                mediaLoading = true,
                mediaError = null,
                hasAlbums = false,
            ),
        )
    }

    @Test
    fun unresolvedScopeIsLoading() {
        assertEquals(
            ArtistDetailCollectionPhase.LOADING,
            artistDetailCollectionPhase(
                scopeReady = false,
                scopeError = null,
                mediaLoading = false,
                mediaError = null,
                hasAlbums = false,
            ),
        )
    }

    @Test
    fun registeredAlbumsStayReadyWhenMediaStoreFails() {
        assertEquals(
            ArtistDetailCollectionPhase.READY,
            artistDetailCollectionPhase(
                scopeReady = true,
                scopeError = null,
                mediaLoading = false,
                mediaError = "MediaStore permission revoked",
                hasAlbums = true,
            ),
        )
    }

    @Test
    fun mediaStoreLoadingWithoutAlbumsIsLoading() {
        assertEquals(
            ArtistDetailCollectionPhase.LOADING,
            artistDetailCollectionPhase(
                scopeReady = true,
                scopeError = null,
                mediaLoading = true,
                mediaError = null,
                hasAlbums = false,
            ),
        )
    }

    @Test
    fun mediaStoreFailureWithoutAlbumsIsAnErrorNeverEmpty() {
        assertEquals(
            ArtistDetailCollectionPhase.MEDIA_ERROR,
            artistDetailCollectionPhase(
                scopeReady = true,
                scopeError = null,
                mediaLoading = false,
                mediaError = "MediaStore failed",
                hasAlbums = false,
            ),
        )
    }

    @Test
    fun aGenuinelyEmptyCollectionIsEmpty() {
        assertEquals(
            ArtistDetailCollectionPhase.EMPTY,
            artistDetailCollectionPhase(
                scopeReady = true,
                scopeError = null,
                mediaLoading = false,
                mediaError = null,
                hasAlbums = false,
            ),
        )
    }
}
