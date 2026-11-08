package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.TrackSource

class AeroDynamicPaletteTest {
    @Test fun dynamicMeshRemainsDisabledUntilPerformanceIsCertified() {
        assertFalse(aeroDynamicMeshEnabled())
        assertTrue(aeroDynamicMeshEnabled(performanceCertified = true))
    }

    @Test fun remoteMediaStoreIdsCannotBorrowLocalAlbumArtwork() {
        val local = aeroLocalPaletteSource(TrackSource.LOCAL, "42", "")
        assertEquals("42", local?.coverId)
        val synced = aeroLocalPaletteSource(TrackSource.SYNCED, "42", "content://media/audio/77")
        assertEquals(null, synced?.coverId)
        assertEquals("content://media/audio/77", synced?.mediaUri)
        assertEquals(null, aeroLocalPaletteSource(TrackSource.SYNCED, "42", "https://server/track"))
        // A numeric remote track reference is NOT a MediaStore audio URI.
        assertEquals(null, aeroLocalPaletteSource(TrackSource.SYNCED, "42", "77"))
        assertEquals(null, aeroLocalPaletteSource(TrackSource.STREAMING, "42", "content://media/audio/77"))
        assertEquals(null, aeroLocalPaletteSource(TrackSource.LOCAL, "", "https://server/image"))
        assertEquals("/storage/emulated/0/Music/a.flac",
            aeroLocalPaletteSource(TrackSource.SYNCED, "42", "/storage/emulated/0/Music/a.flac")?.mediaUri)
    }

    @Test fun noArtworkUsesCanonicalPalette() {
        assertEquals(AeroDefaultDynamicPalette, aeroDynamicPaletteFromPixels(intArrayOf()))
    }

    @Test fun achromaticArtNeverInventsAColor() {
        val grayscale = intArrayOf(0xFF808080.toInt(), 0xFF000000.toInt(), 0xFFFAFAFA.toInt())
        assertEquals(AeroDefaultDynamicPalette, aeroDynamicPaletteFromPixels(grayscale))
    }

    @Test fun colorfulArtworkIsAlwaysDarkAndOpaque() {
        val saturated = IntArray(400) { if (it % 4 == 0) 0xFFEE24A7.toInt() else 0xFF175EE3.toInt() }
        val p = aeroDynamicPaletteFromPixels(saturated)
        assertTrue(listOf(p.upper, p.center, p.lower).all { c ->
            c.red <= 90f / 255f && c.green <= 78f / 255f && c.blue <= 95f / 255f && c.alpha == 1f
        })
    }

    @Test fun transparentArtIsIgnored() {
        assertEquals(AeroDefaultDynamicPalette, aeroDynamicPaletteFromPixels(intArrayOf(0x0023A8F1)))
    }

    @Test fun motionIsDisallowedOutsideActiveAccessiblePlayback() {
        fun allowed(playing: Boolean = true, foreground: Boolean = true,
                    reducedMotion: Boolean = false, highContrast: Boolean = false,
                    powerSave: Boolean = false, lowRam: Boolean = false) =
            aeroDynamicMeshShouldMove(playing, foreground, reducedMotion, highContrast, powerSave, lowRam)
        assertTrue(allowed())
        assertFalse(allowed(playing = false))
        assertFalse(allowed(foreground = false))
        assertFalse(allowed(reducedMotion = true))
        assertFalse(allowed(highContrast = true))
        assertFalse(allowed(powerSave = true))
        assertFalse(allowed(lowRam = true))
    }
    @Test fun frozenMeshNeverJumpsOnPause() {
        val phase = aeroDynamicMeshNextPhase(1.25f, enabled = true)
        assertEquals(phase, aeroDynamicMeshNextPhase(phase, enabled = false), 0f)
        assertTrue(aeroDynamicMeshNextPhase(phase, enabled = true) > phase)
    }

    @Test fun meshPhaseWrapsWithoutUnboundedGrowth() {
        var phase = 0f
        repeat(5000) { phase = aeroDynamicMeshNextPhase(phase, enabled = true) }
        assertTrue(phase >= 0f && phase < 6.2831855f)
        assertEquals(0f, aeroDynamicMeshNextPhase(Float.NaN, enabled = false), 0f)
    }
}
