package org.michimusic.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.cache.TrackDao
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafMediaRepositoryTest {

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
        assertFalse(repo.takePersistablePermission(uri))
    }
}
