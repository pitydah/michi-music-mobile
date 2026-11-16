package org.michimusic.mobile.artist

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * K48-R11 diagnostic: the artist enrichment surface claims "Disponible" from a
 * verified local file, so that exact file must decode through the same ImageLoader
 * the composition uses. A reported failure here means the summary is telling the
 * truth about the license but not about the photo.
 */
@RunWith(AndroidJUnit4::class)
class CachedArtistArtworkDecodeTest {

    @Test
    fun everyCachedArtistPhotoDecodesThroughTheAppImageLoader() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.filesDir, "artist-artwork")
        val files = directory.listFiles { file -> file.isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        Log.i(TAG, "dir=${directory.absolutePath} count=${files.size}")
        files.forEach { file -> Log.i(TAG, "cached=${file.name} bytes=${file.length()}") }
        // A clean CI emulator has no cached artwork yet: that is a skip, not a failure.
        assumeTrue("no cached artist artwork to decode in ${directory.absolutePath}", files.isNotEmpty())

        val loader = SingletonImageLoader.get(context)
        val failures = mutableListOf<String>()
        for (file in files) {
            val request = ImageRequest.Builder(context)
                .data(file)
                .allowHardware(false)
                .size(256)
                .build()
            when (val result = loader.execute(request)) {
                is SuccessResult -> Log.i(TAG, "decoded=${file.name} width=${result.image.width}")
                is ErrorResult -> {
                    Log.e(TAG, "failed=${file.name} cause=${result.throwable}", result.throwable)
                    failures.add("${file.name}: ${result.throwable}")
                }
                else -> failures.add("${file.name}: unexpected ${result::class.java.simpleName}")
            }
        }
        assertTrue("Coil could not decode: ${failures.joinToString()}", failures.isEmpty())
    }

    private companion object {
        const val TAG = "K48ArtworkDecode"
    }
}
