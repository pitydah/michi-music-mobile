package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

class MichiCardDeckTest {
    private data class Item(val id: String)
    private val items = listOf(Item("a"), Item("b"), Item("c"))
    private val key: (Item) -> String = { it.id }

    @Test fun emptyListNeverSelectsAnItem() {
        assertNull(michiCardStepKey(emptyList<Item>(), null, key, 1))
    }

    @Test fun missingSelectionFallsBackToFirstItem() {
        assertEquals(0, michiCardIndex(items, "missing", key))
        assertEquals("b", michiCardStepKey(items, "missing", key, 1))
    }

    @Test fun artistSelectionReconcilesToExistingIdentity() {
        assertEquals("a", michiCardReconciledKey(items, "unknown", key))
        assertEquals("c", michiCardReconciledKey(items, "c", key))
        assertNull(michiCardReconciledKey(emptyList<Item>(), "c", key))
    }

    @Test fun leftAndRightNavigationAreInverseAndCyclic() {
        assertEquals("b", michiCardStepKey(items, "a", key, 1))
        assertEquals("c", michiCardStepKey(items, "a", key, -1))
        assertEquals("a", michiCardStepKey(items, "c", key, 1))
    }

    @Test fun singleCardRemainsSelected() {
        assertEquals("a", michiCardStepKey(items.take(1), "a", key, -1))
        assertEquals("a", michiCardStepKey(items.take(1), "a", key, 1))
    }

    @Test fun stageRespectsAvailableHeightForAlbumsAndArtists() {
        val album = michiCardStageSize(312.dp, 280.dp, circularArtwork = false)
        val artist = michiCardStageSize(312.dp, 280.dp, circularArtwork = true)
        assertEquals(172.dp, album.first)
        assertEquals(280.dp, album.second)
        assertEquals(188.dp, artist.first)
        assertEquals(280.dp, artist.second)
    }

    @Test fun largeFontsReserveMoreHeightForMetadata() {
        val normal = michiCardStageSize(312.dp, 280.dp, circularArtwork = false)
        val large = michiCardStageSize(312.dp, 280.dp, circularArtwork = false, fontScale = 2f)
        assertEquals(280.dp, large.second)
        assertEquals(true, large.first < normal.first)
    }

    @Test fun reorderPreservesIdentitySelection() {
        val reorder = listOf(Item("c"), Item("a"), Item("b"))
        assertEquals(1, michiCardIndex(reorder, "a", key))
        assertEquals("b", michiCardStepKey(reorder, "a", key, 1))
    }

    @Test fun swipeDirectionIsDeterministicAndBidirectional() {
        assertEquals(1, michiCardSwipeStep(-81f, -200f, 400f))
        assertEquals(-1, michiCardSwipeStep(81f, 200f, 400f))
        assertEquals(0, michiCardSwipeStep(79f, 200f, 400f))
    }

    @Test fun flingRequiresMinimumTravelAndMatchingTravelDirection() {
        assertEquals(1, michiCardSwipeStep(-25f, -1800f, 400f))
        assertEquals(-1, michiCardSwipeStep(25f, 1800f, 400f))
        assertEquals(0, michiCardSwipeStep(20f, 3000f, 400f))
        assertEquals(0, michiCardSwipeStep(0f, 3000f, 400f))
        // A fling contrary to the dragged direction must not navigate by velocity alone.
        assertEquals(0, michiCardSwipeStep(-25f, 1800f, 400f))
        assertEquals(0, michiCardSwipeStep(25f, -1800f, 400f))
    }

    @Test fun invalidGeometryNeverCommitsGesture() {
        assertEquals(0, michiCardSwipeStep(-500f, -4000f, 0f))
        assertEquals(0, michiCardSwipeStep(Float.NaN, 120f, 320f))
        assertEquals(0, michiCardSwipeStep(-90f, Float.POSITIVE_INFINITY, 320f))
    }

    @Test fun duplicateLibraryKeysAppearOnceWithoutChangingFirstIdentity() {
        val duplicated = listOf(Item("a"), Item("a"), Item("b"), Item("b"))
        assertEquals(listOf("a", "b"), michiCardDistinctItems(duplicated, key).map(key))
    }

    @Test fun artistFanIsSymmetricAndReducedMotionIsFlat() {
        val first = michiCardDepthStyle(1, true, true)
        val second = michiCardDepthStyle(2, true, true)
        assertEquals(18.dp, first.offsetX)
        assertEquals((-18).dp, second.offsetX)
        assertEquals(6f, first.rotation, 0.001f)
        assertEquals(-6f, second.rotation, 0.001f)
        assertEquals(0f, michiCardDepthStyle(1, true, false).rotation, 0.001f)
    }

    @Test fun highContrastRetainsThreeLayersWithoutRotation() {
        assertEquals(3, michiCardVisibleCount(50, reducedMotion = false))
        assertEquals(1, michiCardVisibleCount(50, reducedMotion = true))
        assertEquals(2, michiCardVisibleCount(2, reducedMotion = false))
        for (circular in listOf(false, true)) {
            val first = michiCardDepthStyle(1, circular, motionEnabled = true, highContrast = true)
            val second = michiCardDepthStyle(2, circular, motionEnabled = true, highContrast = true)
            assertEquals(0f, first.rotation, 0.001f)
            assertEquals(0f, second.rotation, 0.001f)
            assertEquals(true, first.offsetY > 0.dp)
            assertEquals(true, second.offsetY > first.offsetY)
        }
    }

    @Test fun albumFanGrowsDepthWithoutRotatingFront() {
        assertEquals(0f, michiCardDepthStyle(0, false, true).rotation, 0.001f)
        assertEquals((-7).dp, michiCardDepthStyle(1, false, true).offsetX)
        assertEquals(45.dp, michiCardDepthStyle(2, false, true).offsetY)
    }

    @Test fun zeroSizeWindowCannotGenerateNegativeArtwork() {
        val actual = michiCardStageSize(80.dp, 0.dp, circularArtwork = true)
        assertEquals(0.dp, actual.first)
        assertEquals(0.dp, actual.second)
    }

    @Test fun largeAccessibilityFontsShrinkArtBeforeClippingMetadata() {
        val normal = michiCardStageSize(360.dp, 340.dp, circularArtwork = true, fontScale = 1f)
        val enlarged = michiCardStageSize(360.dp, 340.dp, circularArtwork = true, fontScale = 2.4f)
        assertEquals(true, enlarged.first < normal.first)
        assertEquals(340.dp, enlarged.second)
    }

    @Test fun catalogChangeAndSelectionEpochInvalidateOldGesture() {
        val original = MichiCardGestureToken("a", listOf("a", "b", "c"), 4)
        assertEquals(true, michiCardGestureIsCurrent(original, "a", original.itemKeys, 4))
        assertEquals(false, michiCardGestureIsCurrent(original, "b", original.itemKeys, 4))
        assertEquals(false, michiCardGestureIsCurrent(original, "a", listOf("a", "c"), 4))
        assertEquals(false, michiCardGestureIsCurrent(original, "a", original.itemKeys, 5))
    }

    @Test fun extremeFontScaleFallsBackRatherThanClippingEditorialContent() {
        val huge = michiCardStageSize(320.dp, 280.dp, circularArtwork = false, fontScale = 3f)
        assertEquals(true, michiCardNeedsCompactFallback(huge.first, huge.second))
        val tall = michiCardStageSize(360.dp, 600.dp, circularArtwork = true, fontScale = 3f)
        assertEquals(false, michiCardNeedsCompactFallback(tall.first, tall.second))
        assertEquals(true, tall.second <= 600.dp)
    }

    @Test fun fanBleedIsReservedInsideStageAndCompactBoundaryIsDeterministic() {
        val portrait = michiCardStageSize(360.dp, 600.dp, circularArtwork = true)
        assertEquals(328.dp, portrait.first)
        assertEquals(420.dp, portrait.second)
        assertEquals(false, michiCardNeedsCompactFallback(164.dp, 216.dp))
        assertEquals(true, michiCardNeedsCompactFallback(163.dp, 216.dp))
        assertEquals(true, michiCardNeedsCompactFallback(164.dp, 215.dp))
    }

    @Test fun stageRejectsInvalidDimensionsAndDoesNotAllocateHugeWidths() {
        assertEquals(0.dp to 0.dp, michiCardStageSize(80.dp, 0.dp, true))
        assertEquals(0.dp to 0.dp, michiCardStageSize(Dp.Unspecified, 200.dp, false))
        val large = michiCardStageSize(800.dp, 900.dp, false, 1f)
        assertEquals(360.dp, large.first)
    }

    @Test fun artistMemoryKeyDistinguishesDifferentAlbumMembership() {
        val artist = ArtistMosaicEntry("Artist", listOf("10"), albumCount = 1, trackCount = 10)
        val same = artist.copy()
        val different = artist.copy(albumIds = listOf("20"))
        assertEquals(artist, same)
        assertNotEquals(artist, different)
    }

    @Test fun albumAndArtistBackCardsProtrudeAndFitReservedBleed() {
        for (circular in listOf(false, true)) {
            val stage = michiCardStageSize(360.dp, 600.dp, circular)
            val innerHeight = stage.second - 40.dp // Stage inset: 8dp top + 32dp bottom.
            val first = michiCardBottomProtrusion(michiCardDepthStyle(1, circular, true), innerHeight)
            val second = michiCardBottomProtrusion(michiCardDepthStyle(2, circular, true), innerHeight)
            assertEquals(true, first >= 8.dp)
            assertEquals(true, second > first)
            assertEquals(true, second < 32.dp)
        }
    }

    @Test fun rotatedFanRemainsWithinStageBleedForAlbumAndArtist() {
        for (circular in listOf(false, true)) {
            val stage = michiCardStageSize(360.dp, 600.dp, circular)
            val width = stage.first - 48.dp
            val height = stage.second - 40.dp
            val first = michiCardRotatedBottomProtrusion(
                michiCardDepthStyle(1, circular, true), width, height,
            )
            val second = michiCardRotatedBottomProtrusion(
                michiCardDepthStyle(2, circular, true), width, height,
            )
            assertEquals(true, first >= 12.dp)
            assertEquals(true, second > first)
            assertEquals(true, second <= 32.dp)
        }
    }

    @Test fun rearFanIsNotDrawnWhenMotionIsReduced() {
        assertEquals(0.dp, michiCardDepthStyle(2, false, false).offsetY)
        assertEquals(0.dp, michiCardBottomProtrusion(
            michiCardDepthStyle(2, true, false), 380.dp,
        ))
    }

    @Test fun previousPreviewWrapsToLastArtworkAndForwardPreviewWrapsToFirst() {
        assertEquals(2, michiCardPreviewIndex(0, 1, -1, 3))
        assertEquals(0, michiCardPreviewIndex(2, 1, 1, 3))
        assertEquals(1, michiCardPreviewIndex(0, 2, -1, 3))
    }
}
