package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCsvBulkExportTest {
    private val target=MetadataTarget("local","track1","content://media/1","evidence")
    private fun snapshot()=MetadataSnapshot(target,true,mapOf(
        MetadataField.TITLE to listOf("Before"),
        MetadataField.GENRE to listOf("Jazz; Fusion", "Jazz", "Jazz")),
        verifiedFields=setOf(MetadataField.TITLE,MetadataField.GENRE))

    @Test fun reversibleQuotedMultiValueAndDuplicates() {
        val original=snapshot()
        val r=MetadataEditPreview.review(original,listOf(
            MetadataChange.Replace(MetadataField.TITLE,listOf("=new, \"title\"")),
            MetadataChange.Replace(MetadataField.GENRE,listOf("Jazz", "Jazz", "Jazz; Fusion"))))
        assertTrue(r is MetadataReview.Ready)
        val output=MetadataCsvBulkExport.fromReviews(listOf(r),listOf(original))
        assertTrue(output is MetadataCsvBulkExport.Result.Ready)
        val parsed=MetadataCsvBulkImport.review(listOf(original),
            (output as MetadataCsvBulkExport.Result.Ready).csv)
        assertEquals(listOf(r), (parsed as MetadataCsvBulkImport.Result.Ready).reviews)
        assertEquals(1, output.changedTracks)
        assertEquals(4, output.rows)
        assertTrue(output.csv.contains("valor_b64"))
        assertTrue(!output.csv.contains("=new, "))
    }
    @Test fun explicitClearRoundTrips() {
        val x=snapshot()
        val r=MetadataEditPreview.review(x,listOf(MetadataChange.Clear(MetadataField.TITLE)))
        val output=MetadataCsvBulkExport.fromReviews(listOf(r),listOf(x)) as MetadataCsvBulkExport.Result.Ready
        assertTrue(output.csv.contains("\"CLEAR\",\"0\",\"\""))
        assertEquals(listOf(r), (MetadataCsvBulkImport.review(listOf(x),output.csv)
            as MetadataCsvBulkImport.Result.Ready).reviews)
    }
    @Test fun noOwnerOrUriIsExported() {
        val x=snapshot()
        val r=MetadataEditPreview.review(x,listOf(MetadataChange.Replace(MetadataField.TITLE,listOf("After"))))
        val csv=(MetadataCsvBulkExport.fromReviews(listOf(r),listOf(x)) as MetadataCsvBulkExport.Result.Ready).csv
        assertTrue(!csv.contains("content://") && !csv.contains("evidence") && !csv.contains("track1"))
    }
    @Test fun blockedPreviewIsNotExported() {
        assertEquals(MetadataCsvBulkExport.Result.Blocked(MetadataCsvBulkExport.Reason.UNVERIFIED_DIFF,1),
            MetadataCsvBulkExport.fromReviews(listOf(MetadataReview.Blocked(MetadataBlockReason.FIELD_NOT_VERIFIED)),
                listOf(snapshot())))
    }
    @Test fun wrongOriginalCannotBeExported() {
        val x=snapshot()
        val r=MetadataEditPreview.review(x,listOf(MetadataChange.Clear(MetadataField.TITLE)))
        assertTrue(MetadataCsvBulkExport.fromReviews(listOf(r),listOf(x.copy(known=emptyMap())))
            is MetadataCsvBulkExport.Result.Blocked)
    }
    @Test fun noSelectionIsRefused() {
        assertEquals(MetadataCsvBulkExport.Result.Blocked(MetadataCsvBulkExport.Reason.EMPTY_SELECTION),
            MetadataCsvBulkExport.fromReviews(emptyList(),emptyList()))
    }
}
