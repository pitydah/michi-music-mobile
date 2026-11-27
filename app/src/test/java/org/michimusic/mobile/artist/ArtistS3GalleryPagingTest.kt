package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D41 regression gate: bounded Compose inputs and stable navigation, no provider calls. */
class ArtistS3GalleryPagingTest {
    @Test fun emptyResults() {
        val w = artistS3GalleryWindow(0, 0)
        assertEquals(0, w.size)
        assertEquals(0, w.firstNumber)
        assertEquals(0, w.lastNumber)
        assertFalse(w.hasNext)
        assertFalse(w.hasPrevious)
    }
    @Test fun oneResult() {
        val w = artistS3GalleryWindow(1, 0)
        assertEquals(1, w.size)
        assertEquals(1, w.firstNumber)
        assertEquals(1, w.lastNumber)
    }
    @Test fun eightResultsFitOnePage() {
        val w = artistS3GalleryWindow(8, 0)
        assertEquals(8, w.size)
        assertFalse(w.hasNext)
    }
    @Test fun ninthResultStartsSecondPage() {
        val first = artistS3GalleryWindow(9, 0)
        val second = artistS3GalleryWindow(9, first.nextOffset)
        assertEquals(8, first.size)
        assertEquals(8, second.offset)
        assertEquals(1, second.size)
        assertEquals(9, second.firstNumber)
        assertFalse(second.hasNext)
        assertTrue(second.hasPrevious)
    }
    @Test fun sixteenResultsHaveTwoFullPages() {
        val second = artistS3GalleryWindow(16, artistS3GalleryWindow(16, 0).nextOffset)
        assertEquals(8, second.size)
        assertEquals(16, second.lastNumber)
        assertFalse(second.hasNext)
    }
    @Test fun seventeenResultsHaveThreePages() {
        val w = artistS3GalleryWindow(17, 8)
        assertEquals(16, artistS3GalleryWindow(17, w.nextOffset).offset)
        assertEquals(1, artistS3GalleryWindow(17, w.nextOffset).size)
    }
    @Test fun previousPageUsesFixedSize() {
        assertEquals(8, artistS3GalleryWindow(25, 16).previousOffset)
        assertEquals(0, artistS3GalleryWindow(25, 8).previousOffset)
    }
    @Test fun pageOffsetsAlignToEight() {
        assertEquals(8, artistS3GalleryWindow(30, 13).offset)
        assertEquals(16, artistS3GalleryWindow(30, 22).offset)
    }
    @Test fun negativeRequestedOffsetClampsToFirst() {
        assertEquals(0, artistS3GalleryWindow(33, -300).offset)
    }
    @Test fun oversizedOffsetClampsToLastPage() {
        assertEquals(32, artistS3GalleryWindow(33, 9000).offset)
    }
    @Test fun hugeTotalNeverOverflows() {
        val w = artistS3GalleryWindow(Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(w.offset >= 0)
        assertTrue(w.offset <= Int.MAX_VALUE)
        assertTrue(w.size in 0..8)
        assertTrue(w.lastNumber <= Int.MAX_VALUE)
    }
    @Test fun noPageCanComposeMoreThanEightItems() {
        var offset = 0
        var visited = 0
        while (true) {
            val w = artistS3GalleryWindow(1_001, offset)
            assertTrue(w.size <= ARTIST_S3_GALLERY_PAGE_SIZE)
            visited += w.size
            if (!w.hasNext) break
            offset = w.nextOffset
        }
        assertEquals(1_001, visited)
    }
    @Test fun changingPageChangesViewportKey() {
        assertTrue(ArtistS3ViewportKey(ArtistS3PickerScene.GALLERY, 0) !=
            ArtistS3ViewportKey(ArtistS3PickerScene.GALLERY, 8))
    }
    @Test fun openingPreviewChangesViewportKey() {
        assertTrue(ArtistS3ViewportKey(ArtistS3PickerScene.GALLERY, 0) !=
            ArtistS3ViewportKey(ArtistS3PickerScene.PREVIEW, selectedItem = "photo-1"))
    }
    @Test fun changingPreviewCandidateChangesViewportKey() {
        assertTrue(ArtistS3ViewportKey(ArtistS3PickerScene.PREVIEW, selectedItem = "photo-1") !=
            ArtistS3ViewportKey(ArtistS3PickerScene.PREVIEW, selectedItem = "photo-2"))
    }
    @Test fun sameViewportDoesNotCauseRedundantScrollReset() {
        assertEquals(ArtistS3ViewportKey(ArtistS3PickerScene.CONSENT),
            ArtistS3ViewportKey(ArtistS3PickerScene.CONSENT))
    }
    @Test fun focusOnDifferentSlotChangesViewportKey() {
        assertTrue(ArtistS3ViewportKey(ArtistS3PickerScene.CROP, selectedItem = "ARTIST_THUMB") !=
            ArtistS3ViewportKey(ArtistS3PickerScene.CROP, selectedItem = "ARTIST_HERO"))
    }
    @Test fun negativeTotalRejected() {
        var rejected = false
        try { artistS3GalleryWindow(-1, 0) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }
    @Test fun reconfirmationWindowExposesAllTwelveAlbumIdsInOrder() {
        val ids = (1..12).map { "album-$it" }
        val first = artistS3GalleryWindow(ids.size, 0)
        val next = artistS3GalleryWindow(ids.size, first.nextOffset)
        assertEquals(ids, ids.drop(first.offset).take(first.size) + ids.drop(next.offset).take(next.size))
        assertEquals("album-12", ids[next.lastNumber - 1])
        assertEquals(0, next.previousOffset)
    }
}
