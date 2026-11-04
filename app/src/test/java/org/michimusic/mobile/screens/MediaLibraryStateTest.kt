package org.michimusic.mobile.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.data.repository.MediaResult
import java.io.IOException

/**
 * Media Truth precedence. A denied permission or a failed MediaStore query must never
 * reach the UI as an empty library, and a playlist keeps the library non-empty.
 */
class MediaLibraryStateTest {

    @Test
    fun deniedPermissionIsNeverEmpty() {
        assertEquals(
            MediaLibraryState.PERMISSION_DENIED,
            mediaStateFor(MediaResult.PermissionDenied, trackCount = 0, hasPlaylists = false),
        )
    }

    @Test
    fun failedQueryIsAnErrorEvenWithoutTracks() {
        assertEquals(
            MediaLibraryState.QUERY_ERROR,
            mediaStateFor(MediaResult.QueryFailed(IOException("cursor failed")), 0, false),
        )
    }

    @Test
    fun aSuccessfulEmptyQueryIsEmptyOnlyWithoutPlaylists() {
        assertEquals(
            MediaLibraryState.EMPTY,
            mediaStateFor(MediaResult.Success(emptyList<String>()), 0, hasPlaylists = false),
        )
    }

    @Test
    fun aPlaylistSurvivesAnEmptyLocalScan() {
        assertEquals(
            MediaLibraryState.READY,
            mediaStateFor(MediaResult.Success(emptyList<String>()), 0, hasPlaylists = true),
        )
    }

    @Test
    fun tracksMeanReady() {
        assertEquals(
            MediaLibraryState.READY,
            mediaStateFor(MediaResult.Success(listOf("track")), trackCount = 1, hasPlaylists = false),
        )
    }
}
