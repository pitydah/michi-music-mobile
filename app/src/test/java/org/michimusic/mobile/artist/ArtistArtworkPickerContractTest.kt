package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus

/**
 * K48-R07: the picker and the summary make their decisions in pure code, so the two
 * contracts that decide what a user sees - one saved destination versus two, and one
 * column versus two - are pinned here without a device.
 */
class ArtistArtworkPickerContractTest {

    @Test
    fun `a saved destination is reported as saved`() {
        assertEquals(
            "Retrato guardado para mosaico.",
            singleDestinationMessage(ArtworkLoadStatus.READY, "mosaico"),
        )
    }

    @Test
    fun `a rejected licence is named as a licence problem`() {
        assertEquals(
            "El origen no confirma una licencia admitida.",
            singleDestinationMessage(ArtworkLoadStatus.REJECTED, "ficha"),
        )
    }

    @Test
    fun `an unknown failure keeps its own status instead of claiming success`() {
        assertEquals(
            "No fue posible guardar el retrato (provider_error).",
            singleDestinationMessage(ArtworkLoadStatus.PROVIDER_ERROR, "ficha"),
        )
    }

    @Test
    fun `both destinations saved is reported once`() {
        assertEquals(
            "Retrato guardado en mosaico y ficha.",
            bothDestinationsMessage(ArtworkLoadStatus.READY, ArtworkLoadStatus.READY),
        )
    }

    @Test
    fun `a mosaic saved with a failed card is reported as partial`() {
        assertEquals(
            "Retrato guardado en mosaico. La ficha no se pudo guardar (rejected).",
            bothDestinationsMessage(ArtworkLoadStatus.READY, ArtworkLoadStatus.REJECTED),
        )
    }

    @Test
    fun `a card saved with a failed mosaic is reported as partial`() {
        assertEquals(
            "Retrato guardado en ficha. El mosaico no se pudo guardar (provider_error).",
            bothDestinationsMessage(ArtworkLoadStatus.PROVIDER_ERROR, ArtworkLoadStatus.READY),
        )
    }

    @Test
    fun `two failures are never reported as a partial success`() {
        val message = bothDestinationsMessage(ArtworkLoadStatus.CACHE_ERROR, ArtworkLoadStatus.REJECTED)

        assertEquals(
            "No fue posible guardar el retrato en ningún destino " +
                "(cache_error / rejected).",
            message,
        )
        assertFalse(message.contains("guardado en mosaico"))
        assertFalse(message.contains("guardado en ficha"))
    }

    @Test
    fun `three hundred and twenty dp keeps one column`() {
        assertFalse(artworkSlotsInline(widthDp = 320, fontScale = 1f))
    }

    @Test
    fun `three hundred and sixty dp stacks because two columns would clip the CTA`() {
        assertFalse(artworkSlotsInline(widthDp = 360, fontScale = 1f))
    }

    @Test
    fun `three hundred and ninety dp allows two columns at normal text`() {
        assertTrue(artworkSlotsInline(widthDp = 390, fontScale = 1f))
    }

    @Test
    fun `four hundred and twelve and four hundred and eighty dp allow two columns`() {
        assertTrue(artworkSlotsInline(widthDp = 412, fontScale = 1f))
        assertTrue(artworkSlotsInline(widthDp = 480, fontScale = 1f))
    }

    @Test
    fun `one point three font scale still allows two columns at four hundred and eighty dp`() {
        assertTrue(artworkSlotsInline(widthDp = 480, fontScale = 1.3f))
    }

    @Test
    fun `large font scale prefers one column at every width`() {
        listOf(320, 360, 390, 412, 480).forEach { width ->
            assertFalse(
                "width $width with font scale 1.6 must stack",
                artworkSlotsInline(widthDp = width, fontScale = 1.6f),
            )
            assertFalse(
                "width $width with font scale 2.0 must stack",
                artworkSlotsInline(widthDp = width, fontScale = 2.0f),
            )
        }
    }
}
