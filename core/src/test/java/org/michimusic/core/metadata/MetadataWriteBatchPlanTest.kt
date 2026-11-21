package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataWriteBatchPlanTest {
    private fun snapshot(id: String, source: String = "") = MetadataSnapshot(
        MetadataTarget(source, id, "content://media/$id", "proof-$id"),
        tagsVerified = true, known = mapOf(MetadataField.TITLE to listOf("A")),
        verifiedFields = setOf(MetadataField.TITLE))
    private fun review(s: MetadataSnapshot) = MetadataEditPreview.review(s,
        listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("B"))))
    private fun prepare(snapshots: List<MetadataSnapshot>, reviews: List<MetadataReview>) =
        MetadataWriteBatchPlan.prepare(snapshots, reviews,
            snapshots.map { MetadataAudioContainer.FLAC },
            snapshots.map { MetadataDocumentProvider.MEDIASTORE }, snapshots.map { true })

    @Test fun orderedBatchContainsOnlyCandidatesAndNoWritePermission() {
        val a = snapshot("a"); val b = snapshot("b")
        val outcome = prepare(listOf(a,b), listOf(review(a), MetadataReview.Unchanged)) as MetadataWriteBatchPlan.Outcome.Prepared
        assertEquals(1, outcome.candidates.size)
        assertEquals(1, outcome.unchanged)
        assertEquals(0, outcome.candidates.single().index)
        assertFalse(outcome.canWrite)
    }
    @Test fun mixedOwnersAreRejectedEvenWhenUnchanged() {
        val a = snapshot("a"); val b = snapshot("b", "saf-A")
        val result = prepare(listOf(a,b), listOf(review(a), MetadataReview.Unchanged))
        assertEquals(MetadataWriteBatchPlan.BlockReason.MIXED_OWNERS,
            (result as MetadataWriteBatchPlan.Outcome.Blocked).reason)
    }
    @Test fun duplicateUrisAreRejected() {
        val a = snapshot("a"); val b = snapshot("b").copy(target =
            MetadataTarget("", "b", a.target.uri, "proof-b"))
        assertEquals(MetadataWriteBatchPlan.BlockReason.DUPLICATE_TARGET,
            (prepare(listOf(a,b), listOf(review(a), review(b))) as MetadataWriteBatchPlan.Outcome.Blocked).reason)
    }
    @Test fun oneUnverifiedFieldStopsEntireBatch() {
        val a = snapshot("a"); val b = snapshot("b").copy(verifiedFields = emptySet())
        assertTrue(prepare(listOf(a,b), listOf(review(a), review(b))) is MetadataWriteBatchPlan.Outcome.Blocked)
    }
    @Test fun missingSniffedReceiptBlocks() {
        val a = snapshot("a")
        assertEquals(MetadataWriteBatchPlan.BlockReason.INSUFFICIENT_SESSION_PROOF,
            (MetadataWriteBatchPlan.prepare(listOf(a), listOf(review(a)), listOf(MetadataAudioContainer.FLAC),
                listOf(MetadataDocumentProvider.SAF), emptyList()) as MetadataWriteBatchPlan.Outcome.Blocked).reason)
    }
    @Test fun refusedCodecBlocksNoPartialPreparedItems() {
        // ORCHESTRA is stageable in FLAC, but not via the ID3v2.3 field writer.
        // TITLE alone is valid in both, so it must not be used to claim a codec refusal.
        fun orchestra(id:String) = snapshot(id).copy(
            known=mapOf(MetadataField.ORCHESTRA to listOf("Old")),
            verifiedFields=setOf(MetadataField.ORCHESTRA))
        val a=orchestra("a"); val b=orchestra("b")
        fun orchestraReview(s:MetadataSnapshot) = MetadataEditPreview.review(s,
            listOf(MetadataChange.Replace(MetadataField.ORCHESTRA,listOf("New"))))
        val result = MetadataWriteBatchPlan.prepare(listOf(a,b),
            listOf(orchestraReview(a),orchestraReview(b)),
            listOf(MetadataAudioContainer.FLAC,MetadataAudioContainer.ID3_V23),
            listOf(MetadataDocumentProvider.MEDIASTORE,MetadataDocumentProvider.MEDIASTORE),listOf(true,true))
        assertEquals(MetadataWriteBatchPlan.BlockReason.UNSUPPORTED_FIELD,
            (result as MetadataWriteBatchPlan.Outcome.Blocked).reason)
        assertEquals(1,result.index)
    }
    @Test fun batchOf129IsRejected() {
        val a=(1..129).map { snapshot("$it") }
        val result=prepare(a,a.map { review(it) })
        assertEquals(MetadataWriteBatchPlan.BlockReason.TOO_MANY,
            (result as MetadataWriteBatchPlan.Outcome.Blocked).reason)
    }
}
