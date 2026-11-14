package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track

class GenreDetailHeaderPolicyTest {
    @Test fun normalTextStaysInsideTheMobileTargetBand() {
        assertEquals(168, genreDetailHeaderHeightDp(1f))
        assertEquals(168, genreDetailHeaderHeightDp(0.85f))
        assertEquals(168, genreDetailHeaderHeightDp(Float.NaN))
    }

    @Test fun largeTextGrowsButNeverLeaves160To192Dp() {
        listOf(1f, 1.3f, 1.5f, 2f, 3f).forEach { scale ->
            val height = genreDetailHeaderHeightDp(scale)
            assertTrue("fontScale=$scale height=$height", height in 160..192)
        }
        assertEquals(192, genreDetailHeaderHeightDp(2f))
        assertEquals(192, genreDetailHeaderHeightDp(3f))
    }

    @Test fun lowerBandGrowsWithTextAndStaysInsideTheHeader() {
        assertTrue(genreDetailHeaderBandHeightDp(1f) in 60..96)
        assertTrue(genreDetailHeaderBandHeightDp(2f) in 60..96)
        assertTrue("2x band must stay shorter than the 160dp header",
            genreDetailHeaderBandHeightDp(2f) < genreDetailHeaderHeightDp(2f))
        assertTrue(genreDetailHeaderBandHeightDp(Float.NaN) in 60..96)
    }

    // Real approved geometries: genre_pop_editorial.webp (narrow) and
    // genre_rock_editorial.webp (panoramic). The band is the header minus its
    // 68dp lower band, and the panel keeps 20dp side margins.
    private val popRatio = 514f / 268f
    private val rockRatio = 1027f / 280f

    private fun guardAtPanelWidth(ratio: Float, panelWidthDp: Int): Float =
        editorialTitleGuardDp(ratio, (panelWidthDp - 40).toFloat(), 100f)

    @Test fun narrowEditorialPosterNeverShowsTheBakedTitleBlock() {
        listOf(320, 360, 390, 411, 480).forEach { width ->
            val top = editorialVisibleTopFraction(popRatio, (width - 40).toFloat(), 100f)
            assertTrue(
                "panel=${width}dp keeps the baked title zone out of the band " +
                    "(first artwork row=$top, title ends at $EDITORIAL_TITLE_ZONE_FRACTION)",
                top >= EDITORIAL_TITLE_ZONE_FRACTION,
            )
        }
    }

    @Test fun narrowPosterIsGuardedExactlyWhereTheWidthLedCropSlicesTheTitle() {
        // 320dp: the crop would start at 31.5% of the poster, inside the title.
        assertEquals(27.0f, guardAtPanelWidth(popRatio, 320), 0.2f)
        // 360dp: still inside the baked subtitle.
        assertEquals(16.6f, guardAtPanelWidth(popRatio, 360), 0.2f)
        // 390dp: thin guard, still above the title floor.
        assertEquals(8.8f, guardAtPanelWidth(popRatio, 390), 0.2f)
        // 411dp: the approved Phase C width only needs a hairline guard.
        assertTrue("411dp guard must be hairline", guardAtPanelWidth(popRatio, 411) < 4f)
        // 480dp: the crop already starts below the title, so nothing changes.
        assertEquals(0f, guardAtPanelWidth(popRatio, 480), 0.001f)
    }

    @Test fun guardMovesTheVisibleArtworkExactlyToTheTitleFloor() {
        listOf(320, 360, 390, 411).forEach { width ->
            val top = editorialVisibleTopFraction(popRatio, (width - 40).toFloat(), 100f)
            assertEquals(
                "panel=${width}dp first artwork row",
                EDITORIAL_TITLE_ZONE_FRACTION, top, 0.002f,
            )
        }
        // A narrow poster whose 320dp crop already clears the title floor is
        // touched by only a hairline; at 480dp nothing is drawn over the art.
        val salsa = 511f / 346f
        assertEquals("salsa 320dp", EDITORIAL_TITLE_ZONE_FRACTION,
            editorialVisibleTopFraction(salsa, 280f, 100f), 0.002f)
        assertEquals(0f, editorialTitleGuardDp(salsa, 440f, 100f), 0.001f)
        assertTrue("salsa 480dp top",
            editorialVisibleTopFraction(salsa, 440f, 100f) > EDITORIAL_TITLE_ZONE_FRACTION)
    }

    @Test fun panoramicEditorialPostersKeepTheirPhaseCPresentation() {
        listOf(320, 360, 390, 411, 480).forEach { width ->
            assertEquals(
                "panoramic panel=${width}dp must not add a matte",
                0f, guardAtPanelWidth(rockRatio, width), 0.001f,
            )
        }
    }

    @Test fun invalidGeometryNeverGuards() {
        listOf(Float.NaN, 0f, -1f, Float.POSITIVE_INFINITY).forEach { bad ->
            assertEquals(0f, editorialTitleGuardDp(bad, 280f, 100f), 0f)
            assertEquals(0f, editorialTitleGuardDp(popRatio, bad, 100f), 0f)
            assertEquals(0f, editorialTitleGuardDp(popRatio, 280f, bad), 0f)
        }
    }

    @Test fun countLabelNamesBothSongsAndAlbums() {
        val one = LibraryGenre(
            id = "jazz",
            title = "Jazz",
            tracks = listOf(Track(id = "a", title = "A", genre = "Jazz")),
            albumCount = 1,
        )
        assertEquals("1 canción · 1 álbum", genreDetailCountLabel(one))

        val many = one.copy(
            tracks = listOf(
                Track(id = "a", title = "A", genre = "Jazz"),
                Track(id = "b", title = "B", genre = "Jazz"),
            ),
            albumCount = 3,
        )
        assertEquals("2 canciones · 3 álbumes", genreDetailCountLabel(many))
    }
}
