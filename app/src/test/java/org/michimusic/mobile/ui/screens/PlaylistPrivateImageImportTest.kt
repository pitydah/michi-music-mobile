package org.michimusic.mobile.ui.screens

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class PlaylistPrivateImageImportTest {
    private val context = RuntimeEnvironment.getApplication()
    private val importer = PlaylistPrivateImageImporter(context)

    @Before
    fun cleanDirectory() {
        File(context.filesDir, PLAYLIST_PRIVATE_IMAGE_DIRECTORY).deleteRecursively()
    }

    @After
    fun removeDirectory() {
        File(context.filesDir, PLAYLIST_PRIVATE_IMAGE_DIRECTORY).deleteRecursively()
    }

    @Test
    fun importsValidPngAsUniquePrivateCopyAndCleansTemporaryFiles() {
        val first = importPng("p1", "cover") as PlaylistImageImportResult.Success
        val second = importPng("p1", "cover") as PlaylistImageImportResult.Success

        assertNotEquals(first.token, second.token)
        assertEquals("image/png", first.mime)
        assertEquals(64, first.width)
        assertEquals(32, first.height)
        val firstFile = requireNotNull(importer.resolve(first.token))
        assertTrue(firstFile.isFile)
        val root = File(context.filesDir, PLAYLIST_PRIVATE_IMAGE_DIRECTORY).canonicalFile
        assertTrue(firstFile.path.startsWith(root.path + File.separator))
        assertTrue(importer.loadableUri(first.token)!!.startsWith("file://"))
        val names = File(context.filesDir, PLAYLIST_PRIVATE_IMAGE_DIRECTORY)
            .listFiles().orEmpty().map { it.name }
        assertTrue(names.none { it.startsWith("playlist-import-") })
        assertTrue(names.none { it.startsWith("playlist-normalized-") })
        assertTrue(importer.delete(first.token))
        assertTrue(importer.delete(second.token))
    }

    @Test
    fun rejectsBadMetadataAndUndecodableBytesWithoutTouchingExistingCopy() {
        val png = PlaylistTestImages.png64x32
        val good = importPng("p1", "banner") as PlaylistImageImportResult.Success
        val goodFile = requireNotNull(importer.resolve(good.token))

        val badMime = importer.importFromStream(
            "p1", "banner", "text/html", png.size.toLong(), ByteArrayInputStream(png),
        )
        assertEquals(
            PlaylistImageImportFailure.UNSUPPORTED_TYPE,
            (badMime as PlaylistImageImportResult.Failure).reason,
        )
        val tooLarge = importer.importFromStream(
            "p1", "banner", "image/png", PLAYLIST_PRIVATE_IMAGE_MAX_BYTES + 1,
            ByteArrayInputStream(png),
        )
        assertEquals(
            PlaylistImageImportFailure.TOO_LARGE,
            (tooLarge as PlaylistImageImportResult.Failure).reason,
        )
        val garbage = importer.importFromStream(
            "p1", "banner", "image/png", 12, ByteArrayInputStream("not an image".toByteArray()),
        )
        assertEquals(
            PlaylistImageImportFailure.UNSUPPORTED_TYPE,
            (garbage as PlaylistImageImportResult.Failure).reason,
        )

        // A failed replacement must leave the previous private copy intact.
        assertTrue(goodFile.isFile)
        assertNotNull(importer.resolve(good.token))
        importer.delete(good.token)
    }

    private fun importPng(id: String, field: String): PlaylistImageImportResult {
        val bytes = PlaylistTestImages.png64x32
        return importer.importFromStream(
            id, field, "image/png", bytes.size.toLong(), ByteArrayInputStream(bytes),
        )
    }
}
