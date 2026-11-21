package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataBatchSelectionPolicyTest {
    private fun item(id: String, owner: String = "", uri: String = "content://music/$id") =
        MetadataSelectionItem(owner, id, uri, 100L, true)

    @Test fun localBlankOwnerIsValid() {
        assertEquals(MetadataSelectionPlan.Ready(listOf(0)),
            MetadataBatchSelectionPolicy.prepare(listOf(item("one")), setOf(0)))
    }
    @Test fun albumOrderIsPreservedNotSelectionInsertionOrder() {
        assertEquals(MetadataSelectionPlan.Ready(listOf(0, 2)),
            MetadataBatchSelectionPolicy.prepare(listOf(item("a"),item("b"),item("c")), linkedSetOf(2,0)))
    }
    @Test fun emptySelectionIsRejected() {
        assertEquals(MetadataSelectionPlan.Rejected(MetadataSelectionIssue.EMPTY_SELECTION),
            MetadataBatchSelectionPolicy.prepare(listOf(item("a")), emptySet()))
    }
    @Test fun indicesBeyondAlbumAreRejected() {
        assertEquals(MetadataSelectionPlan.Rejected(MetadataSelectionIssue.INVALID_INDEX),
            MetadataBatchSelectionPolicy.prepare(listOf(item("a")), setOf(1)))
    }
    @Test fun allRemoteAreBlocked() {
        assertEquals(MetadataSelectionPlan.Rejected(MetadataSelectionIssue.REMOTE_TRACK),
            MetadataBatchSelectionPolicy.prepare(listOf(item("a").copy(isLocal=false)), setOf(0)))
    }
    @Test fun crossOwnerIsRejectedEvenForSameAlbum() {
        assertEquals(MetadataSelectionPlan.Rejected(MetadataSelectionIssue.MIXED_OWNERS),
            MetadataBatchSelectionPolicy.prepare(listOf(item("a",""),item("b","saf-A")), setOf(0,1)))
    }
    @Test fun duplicateIdsAndUrisAreBlocked() {
        assertEquals(MetadataSelectionPlan.Rejected(MetadataSelectionIssue.DUPLICATE_TRACK),
            MetadataBatchSelectionPolicy.prepare(listOf(item("a"),item("a","","content://other/a")), setOf(0,1)))
        assertEquals(MetadataSelectionPlan.Rejected(MetadataSelectionIssue.DUPLICATE_URI),
            MetadataBatchSelectionPolicy.prepare(listOf(item("a"),item("b","","content://music/a")), setOf(0,1)))
    }
    @Test fun invalidUriAndUnknownSizeAreRejected() {
        assertEquals(MetadataSelectionPlan.Rejected(MetadataSelectionIssue.INVALID_URI),
            MetadataBatchSelectionPolicy.prepare(listOf(item("a").copy(uri="/sdcard/a.flac")), setOf(0)))
        assertEquals(MetadataSelectionPlan.Rejected(MetadataSelectionIssue.MISSING_SIZE),
            MetadataBatchSelectionPolicy.prepare(listOf(item("a").copy(sizeBytes=0)), setOf(0)))
    }
    @Test fun oversizedAlbumSelectionIsRejectedNotTruncated() {
        val items=(0..128).map { item("track-$it") }
        assertEquals(MetadataSelectionPlan.Rejected(MetadataSelectionIssue.TOO_MANY_TRACKS),
            MetadataBatchSelectionPolicy.prepare(items, items.indices.toSet()))
    }
    @Test fun independentlySelectedSubsetOfLargeAlbumIsAllowed() {
        val items=(0..300).map { item("track-$it") }
        val plan=MetadataBatchSelectionPolicy.prepare(items, setOf(199,250))
        assertTrue(plan is MetadataSelectionPlan.Ready)
        assertEquals(listOf(199,250),(plan as MetadataSelectionPlan.Ready).indices)
    }
}
