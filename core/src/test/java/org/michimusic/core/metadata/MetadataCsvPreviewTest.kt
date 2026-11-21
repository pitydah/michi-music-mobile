package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCsvPreviewTest {
    private fun preview(title: String): MetadataReview {
        val original=MetadataSnapshot(MetadataTarget("", "id", "content://private/music/1", "secret-token"),
            tagsVerified=true, known=mapOf(MetadataField.TITLE to listOf("Before")),
            verifiedFields=setOf(MetadataField.TITLE))
        return MetadataEditPreview.review(original, listOf(
            MetadataChange.Replace(MetadataField.TITLE, listOf(title))))
    }
    @Test fun csvQuotesAndEscapesWithoutExposingUri() {
        val output = MetadataCsvPreview.create(listOf(preview("A, \"quoted\"")),
            listOf(MetadataField.TITLE)) as MetadataCsvPreview.Result.Ready
        assertTrue(output.csv.contains("A, \"\"quoted\"\""))
        assertFalse(output.csv.contains("content://"))
        assertFalse(output.csv.contains("secret-token"))
    }
    @Test fun dangerousFormulaIsNeutralized() {
        val result=MetadataCsvPreview.create(listOf(preview(" =HYPERLINK(1)")),
            listOf(MetadataField.TITLE)) as MetadataCsvPreview.Result.Ready
        assertTrue(result.csv.contains("' =HYPERLINK"))
    }
    @Test fun noDataNoExport() {
        assertEquals(MetadataCsvPreview.Result.Blocked(MetadataCsvPreview.Error.NO_SELECTION),
            MetadataCsvPreview.create(emptyList(), listOf(MetadataField.TITLE)))
    }
}
