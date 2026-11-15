package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonsGalleryPagePolicyTest {
    @Test fun correctGalleryNameSurvivesUnderscoreAndCase() {
        assertTrue(CommonsGalleryPagePolicy.matchesRequestedGallery("Midnight_Oil", "midnight oil"))
        assertTrue(CommonsGalleryPagePolicy.matchesRequestedGallery("Björk", "BJÖRK"))
    }
    @Test fun galleryNameMustMatchReturnedMainNamespacePage() {
        assertFalse(CommonsGalleryPagePolicy.matchesRequestedGallery("Artist A", "Artist B"))
        assertFalse(CommonsGalleryPagePolicy.matchesRequestedGallery("Artist A", "Category:Artist A"))
    }
    @Test fun rejectControlAndQueryInjectionAndCategoryTrick() {
        for (candidate in listOf("", "File:X", "Category:Band", "a|b", "..", "A/B", "A?B", "A\u0000B", "A#B", "X".repeat(161))) {
            assertFalse(validCommonsGalleryName(candidate))
        }
    }
    @Test fun ordinaryInternationalGalleryNamesAreAccepted() {
        for (candidate in listOf("Björk", "Игорь Стравинский", "Ravi Shankar", "أم كلثوم")) {
            assertTrue(validCommonsGalleryName(candidate))
        }
    }
}
