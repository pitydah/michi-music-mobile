package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStructuredTagEvidencePolicyTest {
    private val a = "bd6d4f63-5cd0-4d78-a292-167997794c21"
    private val b = "05072650-4b05-4e8b-83d0-dba2576e306a"

    @Test fun sameTrackCanChangeFromTwoIdsToOneWithoutCarryingOldOrdinal() {
        val before = LocalStructuredTagEvidencePolicy.normalize(
            LocalStructuredTagEvidence("song-1", listOf(a,b)))!!
        val after = LocalStructuredTagEvidencePolicy.normalize(
            LocalStructuredTagEvidence("song-1", listOf(b)))!!
        assertEquals(listOf(a,b), before.artistMbids)
        assertEquals(listOf(b), after.artistMbids)
    }

    @Test fun emptyReadStillProducesAReplacementInstruction() {
        assertEquals(LocalStructuredTagEvidence("song-1"),
            LocalStructuredTagEvidencePolicy.normalize(LocalStructuredTagEvidence("song-1")))
    }

    @Test fun rolesDoNotMergeWhenVisibleCreditIsIdentical() {
        val out = LocalStructuredTagEvidencePolicy.normalize(
            LocalStructuredTagEvidence("track", listOf(a), listOf(b)))!!
        assertEquals(listOf(a), out.artistMbids)
        assertEquals(listOf(b), out.albumArtistMbids)
    }

    @Test fun rejectsMaliciousIdsAndDoesNotExtractEmbeddedUuidFromText() {
        assertNull(LocalStructuredTagEvidencePolicy.normalize(LocalStructuredTagEvidence("\u0000bad", listOf(a))))
        assertNull(LocalStructuredTagEvidencePolicy.normalize(LocalStructuredTagEvidence("x".repeat(161))))
        val out = LocalStructuredTagEvidencePolicy.normalize(
            LocalStructuredTagEvidence("ok", listOf("artist:$a", "invalid", a.uppercase())))!!
        assertEquals(listOf(a), out.artistMbids)
        assertTrue(out.albumArtistMbids.isEmpty())
    }

    @Test fun multipleTagsAreBoundedAndDeduplicated() {
        val ids = listOf(a,b,a.uppercase()) + List(50) { "invalid-$it" }
        val out = LocalStructuredTagEvidencePolicy.normalize(
            LocalStructuredTagEvidence("track", ids))!!
        assertEquals(listOf(a,b), out.artistMbids)
    }
    @Test fun twoIndependentExternalIdsRemainRoleScoped() {
        val out = LocalStructuredTagEvidencePolicy.normalize(
            LocalStructuredTagEvidence("song", listOf(a), listOf(b), listOf(b), listOf(a)))!!
        assertEquals(listOf(a), out.artistMbids)
        assertEquals(listOf(b), out.albumArtistMbids)
        assertEquals(listOf(b), out.recordingMbids)
        assertEquals(listOf(a), out.releaseMbids)
    }
    @Test fun successfulTagReadWithoutIdsCanRevokeOldFourKinds() {
        val out = LocalStructuredTagEvidencePolicy.normalize(LocalStructuredTagEvidence("song"))!!
        assertTrue(out.recordingMbids.isEmpty())
        assertTrue(out.releaseMbids.isEmpty())
    }

}
