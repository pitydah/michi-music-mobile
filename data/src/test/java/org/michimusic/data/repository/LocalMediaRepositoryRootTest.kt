package org.michimusic.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.TrackDao
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LocalMediaRepositoryRootTest {
    @Test
    fun customRootReadsOnlyItsIndexedSafRowsWithoutMediaStorePermission() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = MusicRootPreferences(context)
        preferences.setCustomRoot(
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic"),
            "Mi música",
        )
        val trackDao = mockk<TrackDao>()
        every { trackDao.getTracksForSource(CUSTOM_ROOT_SOURCE_ID) } returns flowOf(
            listOf(
                CachedTrack(
                    id = "safroot_one",
                    title = "Canción",
                    artist = "Artista",
                    album = "Álbum",
                    filepath = "content://tree/document/one",
                    downloaded = true,
                    sourceId = CUSTOM_ROOT_SOURCE_ID,
                ),
            ),
        )

        val repository = LocalMediaRepository(
            context = context,
            importedTrackDao = trackDao,
            rootPreferences = preferences,
        )
        val result = repository.queryTracksResult()

        assertTrue(result is MediaResult.Success)
        assertEquals(
            listOf("safroot_one"),
            (result as MediaResult.Success).data.map { it.id },
        )
    }
}
