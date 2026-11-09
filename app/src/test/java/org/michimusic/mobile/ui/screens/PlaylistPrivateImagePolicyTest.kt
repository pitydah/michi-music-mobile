package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistPrivateImagePolicyTest {
    @Test
    fun namesAreUniquePerImportAndOwnedOnlyByTheirPlaylist() {
        val first = playlistPrivateImageName("p1", "cover", "png", "abcdefgh1234")
        val second = playlistPrivateImageName("p1", "cover", "png", "ijklmnop1234")
        assertTrue(first != second)
        assertTrue(first.startsWith(playlistIdHash("p1") + "-cover-"))
        assertTrue(playlistPrivateImageOwnedBy("p1", playlistPrivateImageToken(first)))
        assertFalse(playlistPrivateImageOwnedBy("p2", playlistPrivateImageToken(first)))
        assertFalse(first.contains("/"))
    }

    @Test
    fun tokensRejectTraversalForeignExtensionsAndAbsolutePaths() {
        val name = playlistPrivateImageName("p1", "banner", "jpg", "abcdefgh1234")
        val token = playlistPrivateImageToken(name)
        assertEquals(name, playlistPrivateImageFileName(token))
        assertNull(playlistPrivateImageFileName("michi-playlist://visuals/../../secret.png"))
        assertNull(playlistPrivateImageFileName("michi-playlist://visuals/$name/extra"))
        assertNull(playlistPrivateImageFileName("michi-playlist://visuals/$name.evil"))
        assertNull(playlistPrivateImageFileName("/data/user/0/app/files/$name"))
        assertNull(playlistPrivateImageFileName("content://provider/photo/1"))
    }

    @Test
    fun formatDetectionUsesRealMagicBytesNotClaims() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0,
        )
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0)
        val webpHeader = "RIFF0000WEBP".toByteArray(Charsets.US_ASCII)
        assertEquals(PlaylistPrivateImageFormat.PNG, detectPlaylistPrivateImageFormat(pngHeader))
        assertEquals(PlaylistPrivateImageFormat.JPEG, detectPlaylistPrivateImageFormat(jpegHeader))
        assertEquals(PlaylistPrivateImageFormat.WEBP, detectPlaylistPrivateImageFormat(webpHeader))
        assertNull(detectPlaylistPrivateImageFormat("<svg></svg>".toByteArray()))
        assertNull(detectPlaylistPrivateImageFormat("GIF89a".toByteArray()))
    }

    @Test
    fun dimensionsAndScaleStayBounded() {
        assertTrue(playlistPrivateImageBoundsAllowed(64, 32))
        assertFalse(playlistPrivateImageBoundsAllowed(8, 32))
        assertFalse(playlistPrivateImageBoundsAllowed(9000, 32))
        assertFalse(playlistPrivateImageBoundsAllowed(8000, 8000))
        assertEquals(1f, safePlaylistScale(Float.NaN), 0f)
        assertEquals(1f, safePlaylistScale(-4f), 0f)
        assertEquals(3f, safePlaylistScale(99f), 0f)
    }

    @Test
    fun focalAlignmentUsesClampedBiasInsteadOfCrashingOnCorruption() {
        assertEquals(-1f, playlistFocalAlignment(0f, 0f).horizontalBias, 0f)
        assertEquals(1f, playlistFocalAlignment(1f, 1f).verticalBias, 0f)
        assertEquals(0f, playlistFocalAlignment(Float.NaN, Float.NaN).horizontalBias, 0f)
    }
}
