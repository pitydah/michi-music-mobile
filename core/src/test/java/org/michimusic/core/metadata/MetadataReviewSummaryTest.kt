package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataReviewSummaryTest {
    private fun snapshot() = MetadataSnapshot(
        MetadataTarget("local", "track-1", "content://doc/1", "evidence-1"),
        tagsVerified = true,
        known = mapOf(MetadataField.GENRE to listOf("Rock")),
        verifiedFields = setOf(MetadataField.GENRE),
    )

    private fun draft(value: String) = MetadataEditorDraftSession.start(snapshot())
        .type(MetadataField.GENRE, listOf(value))

    @Test fun touchingTheSameStructuredValueIsNotAnEffectiveChange() {
        val summary = fieldReviewSummary(draft("Rock"))
        assertEquals(0, summary.effectiveFields)
        assertTrue(summary.unchanged)
    }

    @Test fun changedValueCountsOnlyVisibleEffectiveDiffs() {
        val summary = fieldReviewSummary(draft("Hard Rock"))
        assertEquals(1, summary.effectiveFields)
        assertFalse(summary.blocked)
    }

    @Test fun emptyTemporaryInputIsBlockedNotACertifiedChange() {
        val summary = fieldReviewSummary(draft(""))
        assertEquals(0, summary.effectiveFields)
        assertTrue(summary.blocked)
    }

    @Test fun multivalueRoundTripKeepsStructuredList() {
        val changed = MetadataEditorDraftSession.start(snapshot())
            .type(MetadataField.GENRE, listOf("Rock", "Metal"))
        assertEquals(1, fieldReviewSummary(changed).effectiveFields)
        assertEquals(
            MetadataReview.Unchanged,
            changed.type(MetadataField.GENRE, listOf("Rock")).review,
        )
    }
}
