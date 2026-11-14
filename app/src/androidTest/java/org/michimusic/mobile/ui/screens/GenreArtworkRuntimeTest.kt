package org.michimusic.mobile.ui.screens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on Android with the real WebP assets and the app's actual Coil ImageLoader. */
@RunWith(AndroidJUnit4::class)
class GenreArtworkRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun theInstalledApkPublishesBothApprovedCatalogs() {
        val mosaicFiles = context.assets.list(mosaicFolder).orEmpty().toSet()
        val mosaicText = context.assets.open("$mosaicFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        val mosaicOptical = parseUi7OpticalMasks(
            context.assets.open("$mosaicFolder/optical.tsv")
                .bufferedReader().use { it.readText() },
        )
        var mosaicFailure: String? = null
        val mosaic = mosaicApprovedArtwork(
            mosaicText, mosaicFiles, onRejected = { mosaicFailure = it },
            opticalMasks = mosaicOptical,
        )
        assertNull("Mosaico rejected: $mosaicFailure", mosaicFailure)
        assertEquals("Mosaico published entries", 312, mosaic.size)
        assertEquals("Mosaico optical rows", 312, mosaicOptical.size)
        assertEquals("Mosaico verified optical rows", 298,
            mosaic.values.count { it.opticalMask?.verified == true })
        assertEquals("Mosaico unverified optical rows", 14,
            mosaic.values.count { it.opticalMask?.verified == false })
        assertEquals("UI7_MOSAICO_001_Rock.webp", mosaicArtworkForGenre("rock", mosaic)?.filename)
        assertEquals("UI7_MOSAICO_002_Jazz.webp", mosaicArtworkForGenre("jazz", mosaic)?.filename)
        assertEquals("UI7_MOSAICO_312_Timba.webp", mosaicArtworkForGenre("timba", mosaic)?.filename)
        assertEquals("UI7_MOSAICO_275_VisualKei.webp",
            mosaicArtworkForGenre("visual kei", mosaic)?.filename)

        val posterFiles = context.assets.list(editorialPosterFolder).orEmpty().toSet()
        val posterText = context.assets.open("$editorialPosterFolder/manifest.tsv")
            .bufferedReader().use { it.readText() }
        val posterOptical = parseUi7OpticalMasks(
            context.assets.open("$editorialPosterFolder/optical.tsv")
                .bufferedReader().use { it.readText() },
        )
        var posterFailure: String? = null
        val posters = editorialApprovedPosters(
            posterText, posterFiles, onRejected = { posterFailure = it },
            opticalMasks = posterOptical,
        )
        assertNull("Editorial rejected: $posterFailure", posterFailure)
        assertEquals("Editorial published entries", 312, posters.size)
        assertEquals("Editorial optical rows", 312, posterOptical.size)
        assertEquals("Editorial verified optical rows", 227,
            posters.values.count { it.opticalMask?.verified == true })
        assertEquals("Editorial unverified optical rows", 85,
            posters.values.count { it.opticalMask?.verified == false })
        // No approved source is portrait: Editorial full-art is NO-GO until an
        // authentic vertical variant is authorized.
        assertEquals("Editorial portrait sources", 0, posters.values.count { it.isVertical })
        assertEquals("genre_rock_editorial.webp", editorialPosterForTitle("Rock", posters)?.filename)
        assertEquals("genre_jazz_editorial.webp", editorialPosterForTitle("Jazz", posters)?.filename)
        assertEquals("genre_timba_editorial.webp", editorialPosterForTitle("Timba", posters)?.filename)
        assertEquals("genre_visual_kei_editorial.webp",
            editorialPosterForTitle("Visual Kei", posters)?.filename)
    }

    @Test fun coilDecodesInstalledWebpsFromBothViews() = runBlocking {
        val loader = SingletonImageLoader.get(context)
        val paths = listOf(
            "$mosaicFolder/UI7_MOSAICO_001_Rock.webp",
            "$mosaicFolder/UI7_MOSAICO_002_Jazz.webp",
            "$editorialPosterFolder/genre_rock_editorial.webp",
            "$editorialPosterFolder/genre_jazz_editorial.webp",
        )
        paths.forEach { path ->
            context.assets.open(path).use { stream ->
                assertTrue("Empty packaged artwork: $path", stream.read() >= 0)
            }
            val result = loader.execute(ImageRequest.Builder(context)
                .data("file:///android_asset/$path")
                .build())
            assertTrue("Coil failed to decode $path: $result", result is SuccessResult)
        }
    }
}
