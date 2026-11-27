package org.michimusic.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtworkTransformTest {
    private fun source(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(if (format == Bitmap.CompressFormat.PNG) Color.RED else Color.BLUE)
        val out = ByteArrayOutputStream()
        check(bitmap.compress(format, 95, out))
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun lockedAspectJpegProducesExactTargetSize() {
        val result = transformArtwork(
            source(1200, 800, Bitmap.CompressFormat.JPEG), "image/jpeg",
            600, 400, fit = ArtworkFit.CROP, anchor = ArtworkAnchor.CENTER,
        )
        assertEquals(600 to 400, dimensions(result))
    }

    @Test
    fun cropProducesExactSquareWithoutStretching() {
        val result = transformArtwork(
            source(1200, 800, Bitmap.CompressFormat.JPEG), "image/jpeg",
            500, 500, fit = ArtworkFit.CROP, anchor = ArtworkAnchor.CENTER,
        )
        assertEquals(500 to 500, dimensions(result))
    }

    @Test
    fun containProducesExactSizeWithMargins() {
        val result = transformArtwork(
            source(1200, 800, Bitmap.CompressFormat.JPEG), "image/jpeg",
            500, 500, fit = ArtworkFit.CONTAIN, anchor = ArtworkAnchor.CENTER,
        )
        assertEquals(500 to 500, dimensions(result))
    }

    @Test
    fun pngContainKeepsTransparentMargins() {
        val result = transformArtwork(
            source(1200, 800, Bitmap.CompressFormat.PNG), "image/png",
            500, 500, fit = ArtworkFit.CONTAIN, anchor = ArtworkAnchor.CENTER,
        )
        assertEquals(500 to 500, dimensions(result))
        val bitmap = BitmapFactory.decodeByteArray(result, 0, result.size)
        assertEquals(0, Color.alpha(bitmap.getPixel(2, 2)))
        bitmap.recycle()
    }
}
