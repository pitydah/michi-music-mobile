package org.michimusic.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.TrackDao
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LocalMediaRepositoryRootTest {
    @Before
    fun clearRootPreferences() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("michi_library", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun releasePersistedGrants() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        resolver.persistedUriPermissions.toList().forEach {
            resolver.releasePersistableUriPermission(
                it.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    @Test
    fun customRootReadsOnlyItsIndexedSafRowsWithoutMediaStorePermission() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = MusicRootPreferences(context)
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        preferences.setCustomRoot(treeUri, "Mi música")
        val sourceId = customRootSourceId(treeUri)
        val trackDao = mockk<TrackDao>()
        every { trackDao.getTracksForSource(sourceId) } returns flowOf(
            listOf(
                CachedTrack(
                    id = "safroot_one",
                    title = "Canción",
                    artist = "Artista",
                    album = "Álbum",
                    filepath = "content://tree/document/one",
                    downloaded = true,
                    sourceId = sourceId,
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

    @Test
    fun revokedCustomGrantReturnsRecoverableFailureInsteadOfStaleSuccess() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        MusicRootPreferences(context).setCustomRoot(treeUri, "Mi música")
        val repository = LocalMediaRepository(
            context = context,
            importedTrackDao = mockk(relaxed = true),
            rootPreferences = MusicRootPreferences(context),
        )

        val result = repository.queryTracksResult()

        assertTrue(result is MediaResult.QueryFailed)
        assertTrue((result as MediaResult.QueryFailed).cause.message.orEmpty().contains("permiso"))
    }
}
