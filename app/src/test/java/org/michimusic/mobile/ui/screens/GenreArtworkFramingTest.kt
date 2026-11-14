package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Framing policy: no stretch, no FillBounds, no arbitrary zoom, no blur.
 * A compatible container takes an explicitly bounded centered crop; anything
 * else keeps the whole figure on the card's designed surface.
 */
class GenreArtworkFramingTest {
    @Test fun nearMatchingContainersTakeAModerateCenteredCrop() {
        assertEquals(GenreArtworkFraming.MODERATE_CROP,
            genreArtworkFraming(1.44f, 1.44f))
        // 320dp focal card: 280x218 = 1.28 against the approved 1.44 artwork.
        assertEquals(GenreArtworkFraming.MODERATE_CROP,
            genreArtworkFraming(1.44f, 1.28f))
        // 411dp focal card: 371x218 = 1.70, still <= 1.22x from the artwork.
        assertEquals(GenreArtworkFraming.MODERATE_CROP,
            genreArtworkFraming(1.44f, 1.70f))
        // The approved pack is 1.32..1.56, so it is compatible with the 1.41 frame.
        (listOf(1.32f, 1.36f, 1.41f, 1.48f, 1.56f)).forEach { ratio ->
            assertEquals("ratio $ratio",
                GenreArtworkFraming.MODERATE_CROP,
                genreArtworkFraming(ratio, GENRE_ARTWORK_FRAME_RATIO))
        }
    }

    @Test fun incompatibleContainersKeepTheWholeFigureOnAMatte() {
        // A two-column portrait card is ~0.93:1: a 1.44:1 cat would lose ~35%
        // of its width, so the card keeps the figure and extends its own surface.
        assertEquals(GenreArtworkFraming.CONTAIN_WITH_MATTE,
            genreArtworkFraming(1.44f, 0.93f))
        assertEquals(GenreArtworkFraming.CONTAIN_WITH_MATTE,
            genreArtworkFraming(1.44f, 2.4f))
        assertEquals(GenreArtworkFraming.CONTAIN_WITH_MATTE,
            genreArtworkFraming(1.44f, 0.5f))
    }

    @Test fun invalidGeometryFailsClosedToTheNonDestructiveMode() {
        assertEquals(GenreArtworkFraming.CONTAIN_WITH_MATTE, genreArtworkFraming(null, 1.4f))
        assertEquals(GenreArtworkFraming.CONTAIN_WITH_MATTE, genreArtworkFraming(1.4f, null))
        assertEquals(GenreArtworkFraming.CONTAIN_WITH_MATTE, genreArtworkFraming(0f, 1.4f))
        assertEquals(GenreArtworkFraming.CONTAIN_WITH_MATTE,
            genreArtworkFraming(Float.NaN, 1.4f))
        assertEquals(GenreArtworkFraming.CONTAIN_WITH_MATTE,
            genreArtworkFraming(1.4f, Float.POSITIVE_INFINITY))
    }

    @Test fun containerRatioNeedsPositiveFiniteBounds() {
        assertEquals(1.5f, genreArtworkContainerRatio(300f, 200f))
        assertNull(genreArtworkContainerRatio(0f, 200f))
        assertNull(genreArtworkContainerRatio(300f, 0f))
        assertNull(genreArtworkContainerRatio(Float.NaN, 200f))
        assertNull(genreArtworkContainerRatio(300f, Float.POSITIVE_INFINITY))
    }

    @Test fun toleranceIsBoundedSoACropCannotBeDestructive() {
        assertEquals(1.22f, GENRE_ARTWORK_CROP_TOLERANCE)
        // At the tolerance boundary a crop removes at most ~18% of one axis.
        assertEquals(GenreArtworkFraming.MODERATE_CROP,
            genreArtworkFraming(1f, GENRE_ARTWORK_CROP_TOLERANCE))
        assertEquals(GenreArtworkFraming.CONTAIN_WITH_MATTE,
            genreArtworkFraming(1f, GENRE_ARTWORK_CROP_TOLERANCE + 0.01f))
    }
}
