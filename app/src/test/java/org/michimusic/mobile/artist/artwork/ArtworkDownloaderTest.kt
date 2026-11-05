package org.michimusic.mobile.artist.artwork

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtworkDownloaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun png(width: Int = 1000, height: Int = 600): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun client(
        bytes: ByteArray = png(),
        contentType: String = "image/png",
        code: Int = 200,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error")
                .header("Content-Type", contentType)
                .body(bytes.toResponseBody(contentType.toMediaType()))
                .build()
        })
        .build()

    private fun candidate(url: String = "https://upload.wikimedia.org/wikipedia/commons/a/ab/test.png") =
        ArtistArtworkCandidate(
            source = ArtworkSource.COMMONS_P18,
            stableAssetId = "commons:test.png",
            confirmedMbid = "05072650-4b05-4e8b-83d0-dba2576e306a",
            verifiedQid = "Q48284",
            canonicalSourcePage = "https://commons.wikimedia.org/wiki/File:Test.png",
            imageUrl = url,
            originalWidth = 1000,
            originalHeight = 600,
            mimeType = "image/png",
            licenseId = "CC BY-SA 2.0",
            licenseUrl = "https://creativecommons.org/licenses/by-sa/2.0/",
            author = "Author",
            attribution = "Author",
            evidence = ArtworkEvidence.VERIFIED_QID_REFERENCE,
            rights = ArtworkRights.VERIFIED_ALLOWED,
        )

    @Test
    fun decodesWritesAndHashesTheDownloadInsteadOfTrustingTheUrl() = runBlocking {
        val downloaded = ArtworkDownloader(context, client()).download(candidate())
        assertEquals(1000, downloaded.width)
        assertEquals(600, downloaded.height)
        assertEquals("image/png", downloaded.mimeType)
        assertTrue(downloaded.relativePath.startsWith("artist-artwork/"))
        assertTrue(downloaded.sha256.matches(Regex("^[0-9a-f]{64}$")))
        val file = java.io.File(context.filesDir, downloaded.relativePath)
        assertTrue(file.isFile && file.length() > 0)
    }

    @Test
    fun rejectsNonImageMimeAndHttpErrorsBeforeCreatingACacheEntry() = runBlocking {
        try {
            ArtworkDownloader(context, client(contentType = "text/plain")).download(candidate())
            fail("Expected MIME rejection")
        } catch (expected: ArtworkCacheException) {
            assertTrue(expected.message.orEmpty().contains("Tipo"))
        }
        try {
            ArtworkDownloader(context, client(code = 403)).download(candidate())
            fail("Expected HTTP rejection")
        } catch (expected: ArtworkHttpException) {
            assertEquals(403, expected.status)
        }
    }

    @Test
    fun rejectsRealHostsThatAreNotInTheLicensedOriginAllowlist() = runBlocking {
        try {
            ArtworkDownloader(context, client()).download(candidate("https://evil.example/test.png"))
            fail("Expected origin rejection")
        } catch (expected: ArtworkCacheException) {
            assertTrue(expected.message.orEmpty().contains("Origen"))
        }
    }

    @Test
    fun cachedBytesAreVerifiedAndMissingFilesBecomeExplicitCacheErrors() = runBlocking {
        val downloaded = ArtworkDownloader(context, client()).download(candidate())
        val selection = ArtistArtworkSelection(
            slot = ArtworkSlot.ARTIST_HERO,
            assetId = "sha256:${downloaded.sha256}",
            confirmedMbid = "05072650-4b05-4e8b-83d0-dba2576e306a",
            localImagePath = java.io.File(context.filesDir, downloaded.relativePath).absolutePath,
            focus = ArtworkFocus(0.5f, 0.4f),
            manuallyChosen = false,
            revision = 1L,
        )
        assertEquals(ArtworkLoadStatus.READY, verifyCachedArtwork(context, selection).status)
        val missing = verifyCachedArtwork(context, selection.copy(localImagePath = null))
        assertEquals(ArtworkLoadStatus.CACHE_ERROR, missing.status)
    }

    @Test
    fun networkIOExceptionIsPropagatedAsAFailureNotASuccess() = runBlocking {
        val failing = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw IOException("offline") })
            .build()
        try {
            ArtworkDownloader(context, failing).download(candidate())
            fail("Expected IO failure")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("offline"))
        }
    }
}
