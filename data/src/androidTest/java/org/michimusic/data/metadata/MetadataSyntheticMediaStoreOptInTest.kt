package org.michimusic.data.metadata

import android.content.ContentValues
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * ME-08 OPTIONAL EXTERNAL GATE. Writes ONLY a brand-new, app-owned IS_PENDING
 * MediaStore item and always attempts to delete it. Never selects existing audio.
 * Deliberately ignored in ordinary instrumented test runs: OpenCode must opt in.
 */
@Ignore("Explicit opt-in required for an app-owned synthetic MediaStore fixture")
@RunWith(AndroidJUnit4::class)
class MetadataSyntheticMediaStoreOptInTest {
    @Test fun appOwnedPendingItemCanBeReopenedAfterWrite() {
        val resolver=InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val label="me08-synthetic-${UUID.randomUUID()}.mp3"
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,label)
            put(MediaStore.MediaColumns.MIME_TYPE,"audio/mpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Music/MichiME08TestOnly/")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val uri=resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values)
        assertNotNull("No app-owned test item could be created",uri)
        val exact=uri!!
        try {
            val bytes=byteArrayOf(0x49,0x44,0x33,0,0,0,0,0,0,0,1,2,3,4)
            val handle=resolver.openFileDescriptor(exact,"rwt")!!
            ParcelFileDescriptor.AutoCloseOutputStream(handle).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            assertArrayEquals(bytes,resolver.openInputStream(exact)!!.use { it.readBytes() })
        } finally {
            // Preserve the exact created URI; never delete by query or filename.
            resolver.delete(exact,null,null)
        }
    }
}
