package org.michimusic.mobile.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises the real ZXing decoder with codes produced by ZXing's own encoder, so the
 * pairing scanner is proven end to end without a physical camera: the emulator only shows
 * a virtual scene, and a green screen is not evidence that decoding works.
 */
class QrDecoderTest {

    private val payload =
        "michi://pair?format=michi-link-pairing&version=1&session_id=abc"

    private fun qrLuminance(text: String, size: Int = 240): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        // ZXing luminance: 0 is black, 255 is white.
        return ByteArray(size * size) { index ->
            if (matrix[index % size, index / size]) 0.toByte() else 0xFF.toByte()
        }
    }

    @Test
    fun `a generated pairing link is decoded back unchanged`() {
        val size = 240
        assertEquals(payload, decodeQrLuminance(qrLuminance(payload, size), size, size))
    }

    @Test
    fun `an empty frame decodes to nothing instead of throwing`() {
        val size = 240
        assertNull(decodeQrLuminance(ByteArray(size * size) { 0xFF.toByte() }, size, size))
    }

    @Test
    fun `a buffer smaller than the declared frame is rejected`() {
        assertNull(decodeQrLuminance(ByteArray(16), 240, 240))
    }

    @Test
    fun `quarter turn moves corners the way CameraX reports rotation`() {
        // 2x2 source:  1 2
        //              3 4
        val src = byteArrayOf(1, 2, 3, 4)
        // Counter clockwise puts the right column on top: 2 4 1 3
        assertEquals(listOf(2, 4, 1, 3), rotateQuarterTurns(src, 2, 2, clockwise = false).map { it.toInt() })
        // Clockwise puts the left column on top:                 3 1 4 2
        assertEquals(listOf(3, 1, 4, 2), rotateQuarterTurns(src, 2, 2, clockwise = true).map { it.toInt() })
    }

    @Test
    fun `half turn reverses the whole buffer`() {
        assertEquals(
            listOf(4, 3, 2, 1),
            rotateHalfTurn(byteArrayOf(1, 2, 3, 4), 2, 2).map { it.toInt() },
        )
    }
}
