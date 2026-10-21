package org.michimusic.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.TrackDao
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafMediaRepositoryTest {

    @Test
    fun `importAudioUri stores track with saf prefix and calls dao`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val trackDao = mockk<TrackDao>(relaxed = true)
        val repo = SafMediaRepository(context, trackDao)

        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic%2Ftest.mp3")
        val trackSlot = slot<CachedTrack>()
        coEvery { trackDao.insert(capture(trackSlot)) } returns Unit

        val imported = repo.importAudioUri(uri)

        assertNotNull(imported)
        coVerify(exactly = 1) { trackDao.insert(any()) }
        assertEquals("test", imported?.title)
        assertEquals(uri.toString(), imported?.filepath)
        assertEquals("saf_${uri.hashCode().toUInt()}", imported?.id)
    }

    @Test
    fun `takePersistablePermission catches SecurityException and returns false`() {
        val context = mockk<Context>()
        val contentResolver = mockk<ContentResolver>()
        val trackDao = mockk<TrackDao>(relaxed = true)

        every { context.contentResolver } returns contentResolver
        val uri = Uri.parse("content://nonexistent.authority/item")
        every {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } throws SecurityException("No permission")

        val repo = SafMediaRepository(context, trackDao)
        val success = repo.takePersistablePermission(uri)
        assertEquals(false, success)
    }
}
