package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCsvBulkImportTest {
    private val target = MetadataTarget("", "track1", "content://local/1", "read-evidence")
    private fun snapshot(id: String = "track1") = MetadataSnapshot(
        target.copy(trackId=id,uri="content://local/$id"), true,
        mapOf(MetadataField.TITLE to listOf("Original"),
            MetadataField.GENRE to listOf("Jazz", "Rock")),
        verifiedFields = setOf(MetadataField.TITLE, MetadataField.GENRE))
    private fun run(body: String, tracks: List<MetadataSnapshot> = listOf(snapshot())) =
        MetadataCsvBulkImport.review(tracks, MetadataCsvBulkImport.HEADER + "\r\n" + body)
    private fun blocked(body: String, expected: MetadataCsvBulkImport.Issue) {
        val response=run(body)
        assertTrue(response is MetadataCsvBulkImport.Result.Blocked)
        assertEquals(expected,(response as MetadataCsvBulkImport.Result.Blocked).reason)
    }
    @Test fun replacesOneFieldWithoutTouchingOtherFields() {
        val result=run("1,TITLE,REPLACE,1,New\r\n") as MetadataCsvBulkImport.Result.Ready
        val edited=result.reviews.single() as MetadataReview.Ready
        assertEquals(listOf("New"),edited.proposed.known[MetadataField.TITLE])
        assertEquals(listOf("Jazz","Rock"),edited.proposed.known[MetadataField.GENRE])
    }
    @Test fun orderedDuplicateValuesSurviveImport() {
        val input="1,GENRE,REPLACE,1,Jazz\r\n1,GENRE,REPLACE,2,Jazz\r\n"
        val result=run(input) as MetadataCsvBulkImport.Result.Ready
        assertEquals(listOf("Jazz","Jazz"),
            (result.reviews.single() as MetadataReview.Ready).proposed.known[MetadataField.GENRE])
    }
    @Test fun omissionIsKeepNotClear() {
        val result=run("") as MetadataCsvBulkImport.Result.Ready
        assertEquals(0,result.edits);assertEquals(1,result.unchanged)
    }
    @Test fun explicitClearRequiresIndexZero() {
        val result=run("1,GENRE,CLEAR,0,\r\n") as MetadataCsvBulkImport.Result.Ready
        assertTrue((result.reviews.single() as MetadataReview.Ready)
            .proposed.known[MetadataField.GENRE].isNullOrEmpty())
    }
    @Test fun refusesAccidentalClear() = blocked("1,GENRE,CLEAR,1,\r\n",
        MetadataCsvBulkImport.Issue.INVALID_VALUE)
    @Test fun refusesWrongSelectionIndex() = blocked("2,TITLE,REPLACE,1,Hi\r\n",
        MetadataCsvBulkImport.Issue.INVALID_TRACK)
    @Test fun refusesUnverifiedField() = blocked("1,ALBUM,REPLACE,1,Hi\r\n",
        MetadataCsvBulkImport.Issue.FIELD_NOT_VERIFIED)
    @Test fun refusesUnknownTagName() = blocked("1,UNKNOWN,REPLACE,1,Hi\r\n",
        MetadataCsvBulkImport.Issue.UNKNOWN_FIELD)
    @Test fun refusesMixedActions() = blocked("1,TITLE,CLEAR,0,\r\n1,TITLE,REPLACE,1,Hi\r\n",
        MetadataCsvBulkImport.Issue.MIXED_ACTIONS)
    @Test fun refusesPositionGap() = blocked("1,GENRE,REPLACE,2,Jazz\r\n",
        MetadataCsvBulkImport.Issue.MISSING_POSITION)
    @Test fun refusesDuplicatePosition() = blocked("1,GENRE,REPLACE,1,Jazz\r\n1,GENRE,REPLACE,1,Rock\r\n",
        MetadataCsvBulkImport.Issue.DUPLICATE_POSITION)
    @Test fun supportsQuotedCommasAndNewlines() {
        val response=run("1,TITLE,REPLACE,1,\"A,\"\"B\"\"\"\r\n")
        assertTrue(response is MetadataCsvBulkImport.Result.Ready)
    }
    @Test fun refusesWrongHeader() = assertTrue(
        MetadataCsvBulkImport.review(listOf(snapshot()), "track,field\r\n")
            is MetadataCsvBulkImport.Result.Blocked)
    @Test fun refusesDuplicateTrackInSelection() = assertEquals(
        MetadataCsvBulkImport.Result.Blocked(MetadataCsvBulkImport.Issue.INVALID_SELECTION),
        MetadataCsvBulkImport.review(listOf(snapshot(),snapshot()),MetadataCsvBulkImport.HEADER))
}
