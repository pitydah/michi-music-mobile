package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroPaletteCachePolicyTest {
    @Test fun failedArtworkIsNotCachedForever() {
        val missed = aeroPaletteCacheEntry(AeroDefaultDynamicPalette, decodedArtwork = false, elapsedMs = 10L)
        assertTrue(aeroPaletteCacheUsable(missed, 29_999L))
        assertFalse(aeroPaletteCacheUsable(missed, 30_010L))
        assertFalse(aeroPaletteCacheUsable(null, 0L))
    }

    @Test fun monochromeDecodedArtIsStillAValidPositiveHit() {
        val decoded = aeroPaletteCacheEntry(AeroDefaultDynamicPalette, decodedArtwork = true, elapsedMs = 10L)
        assertTrue(aeroPaletteCacheUsable(decoded, 900_000L))
        assertEquals(10L + AERO_PALETTE_POSITIVE_TTL_MILLIS, decoded.validUntilElapsedMs)
        assertFalse(aeroPaletteCacheUsable(decoded, 10L + AERO_PALETTE_POSITIVE_TTL_MILLIS))
    }

    @Test fun onlyLocalSourcesAuthorizeExtraction() {
        assertTrue(aeroPaletteHasLocalSource("123", null))
        assertFalse(aeroPaletteHasLocalSource(null, "123"))
        assertFalse(aeroPaletteHasLocalSource(null, "-1"))
        assertTrue(aeroPaletteHasLocalSource("content://media/external/audio/albumart/42", null))
        assertTrue(aeroPaletteHasLocalSource("file:///music/album.jpg", null))
        assertTrue(aeroPaletteHasLocalSource(null, "content://com.example/track"))
        assertFalse(aeroPaletteHasLocalSource("https://example.invalid/cover.jpg", null))
        assertFalse(aeroPaletteHasLocalSource(null, null))
        assertFalse(aeroPaletteHasLocalSource("", ""))
    }

    @Test fun powerSavingDisablesArtworkSamplingWithoutChangingPlayback() {
        assertFalse(aeroShouldLoadDynamicPalette(
            foreground = true, highContrast = false, lowRamDevice = false,
            coverId = "123", mediaUri = null, powerSaveMode = true,
        ))
        assertTrue(aeroShouldLoadDynamicPalette(
            foreground = true, highContrast = false, lowRamDevice = false,
            coverId = "123", mediaUri = null, powerSaveMode = false,
        ))
    }

    @Test fun invalidSourceNeverCreatesAFakeLocalPalette() {
        // A local downloaded file remains eligible even if its optional cover URL is invalid.
        assertTrue(aeroPaletteHasLocalSource("ftp://example.invalid/cover", "/storage/file.mp3"))
        assertFalse(aeroPaletteHasLocalSource("ftp://example.invalid/cover", "https://example.invalid/audio"))
        assertFalse(aeroPaletteHasLocalSource(null, "//remote/file.mp3"))
    }

    @Test fun backgroundNeverStartsAnArtworkDecode() {
        fun eligible(foreground: Boolean = true, highContrast: Boolean = false,
                     lowRam: Boolean = false, uri: String? = "content://music/cover") =
            aeroShouldLoadDynamicPalette(foreground, highContrast, lowRam, uri, null)
        assertTrue(eligible())
        assertFalse(eligible(foreground = false))
        assertFalse(eligible(highContrast = true))
        assertFalse(eligible(lowRam = true))
        assertFalse(eligible(uri = null))
        assertFalse(eligible(uri = "https://example.invalid/image.png"))
    }

    @Test fun retryExpiryNeverOverflowsClock() {
        val entry = aeroPaletteCacheEntry(AeroDefaultDynamicPalette, false, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, entry.validUntilElapsedMs)
    }
}
