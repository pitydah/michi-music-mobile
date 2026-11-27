package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataReview

/**
 * The workbench may only bundle what the certified write path supports. A field
 * the container cannot verify stays provisional, so exposing it as a saveable
 * control would be a placebo affordance: the bundle must refuse it before any
 * preview or commit is staged.
 */
class MetadataWorkbenchUnsupportedFieldsTest {
    @Test
    fun provisionalFieldsBlockSaveAndUnchangedReviewHasNoBundle() {
        val blocked = MetadataWorkbenchSaveBundlePolicy.build(
            scalarReview = MetadataReview.Unchanged,
            artLyrics = MetadataArtLyricsUiState(),
            provisionalCount = 1,
        )
        assertTrue("expected Blocked but got $blocked", blocked is MetadataWorkbenchSaveBundlePolicy.Result.Blocked)
        assertEquals(
            MetadataWorkbenchSaveBundlePolicy.Reason.PROVISIONAL_FIELDS,
            (blocked as MetadataWorkbenchSaveBundlePolicy.Result.Blocked).reason,
        )

        // Refusal is not an accidental side effect of an empty review: with every
        // field resolved the same review reports the honest "no changes" reason.
        val empty = MetadataWorkbenchSaveBundlePolicy.build(
            scalarReview = MetadataReview.Unchanged,
            artLyrics = MetadataArtLyricsUiState(),
            provisionalCount = 0,
        )
        assertTrue("expected Blocked but got $empty", empty is MetadataWorkbenchSaveBundlePolicy.Result.Blocked)
        assertEquals(
            MetadataWorkbenchSaveBundlePolicy.Reason.NO_EFFECTIVE_CHANGES,
            (empty as MetadataWorkbenchSaveBundlePolicy.Result.Blocked).reason,
        )
    }
}
