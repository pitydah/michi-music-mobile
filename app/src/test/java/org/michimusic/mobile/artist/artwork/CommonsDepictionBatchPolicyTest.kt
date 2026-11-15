package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonsDepictionBatchPolicyTest {
    private val qid = "Q48284"
    @Test fun seventhFileIsDiscoverableWithoutUnboundedEnumeration() {
        val files = (0 until 24).map { "Photograph_$it.jpg" }
        val selected = CommonsDepictionBatchPolicy.selectFileTitles(files)
        assertTrue("Photograph_6.jpg" in selected)
        assertEquals(12, selected.size)
        assertFalse("Photograph_18.jpg" in selected)
    }

    @Test fun galleryMayExamine24FilesButEachIdentityRequestRemains12() {
        val files = (0 until 40).map { "Photograph_$it.jpg" }
        assertEquals(24, CommonsDepictionBatchPolicy.selectFileTitles(files, 24).size)
        assertEquals(12, CommonsDepictionBatchPolicy.selectFileTitles(files).size)
        assertEquals(24, CommonsDepictionBatchPolicy.selectFileTitles(files, 10000).size)
        assertEquals(0, CommonsDepictionBatchPolicy.selectFileTitles(files, -10).size)
        assertEquals(listOf("Same_photo.jpg"),
            CommonsDepictionBatchPolicy.selectFileTitles(
                listOf("Same_photo.jpg", "Same photo.jpg", "File:Bad.jpg"), 24))
    }

    @Test fun craftedPipesControlsAndDuplicateAliasesCannotExtendBatch() {
        val files = listOf("Good_A.jpg", "Good A.jpg", "File:Other.jpg", "A|B.jpg", "evil\nfile.jpg", "Normal.jpg")
        assertEquals(listOf("Good_A.jpg", "Normal.jpg"), CommonsDepictionBatchPolicy.selectFileTitles(files))
    }

    @Test fun onlyExactOwnPageP180CanProveAnArtist() {
        val pages = listOf(
            CommonsDepictionBatchPolicy.Page("File:Not_artist.jpg", "41"),
            CommonsDepictionBatchPolicy.Page("File:Actual_artist.jpg", "42"),
        )
        val evidence = mapOf("M41" to setOf("Q999999"), "M42" to setOf(qid))
        assertEquals(setOf("Actual_artist.jpg"), CommonsDepictionBatchPolicy.verifiedFileTitles(
            listOf("Not_artist.jpg", "Actual_artist.jpg"), pages, evidence, qid))
    }

    @Test fun unrelatedPageTitleAndInvalidPageIdCannotProvideIdentity() {
        val pages = listOf(
            CommonsDepictionBatchPolicy.Page("File:Impostor.jpg", "42"),
            CommonsDepictionBatchPolicy.Page("File:Actual_artist.jpg", "-4"),
        )
        assertTrue(CommonsDepictionBatchPolicy.verifiedFileTitles(
            listOf("Actual_artist.jpg"), pages, mapOf("M42" to setOf(qid)), qid).isEmpty())
    }

    @Test fun duplicatePageRowsAreAmbiguousEvenWhenOneHasAProof() {
        val page = CommonsDepictionBatchPolicy.Page("File:Actual_artist.jpg", "42")
        assertTrue(CommonsDepictionBatchPolicy.verifiedFileTitles(
            listOf("Actual_artist.jpg"), listOf(page, page), mapOf("M42" to setOf(qid)), qid).isEmpty())
    }

    @Test fun malformedArtistQidDoesNotAuthorizeAnything() {
        assertTrue(CommonsDepictionBatchPolicy.verifiedFileTitles(
            listOf("Actual_artist.jpg"),
            listOf(CommonsDepictionBatchPolicy.Page("File:Actual_artist.jpg", "42")),
            mapOf("M42" to setOf(qid)), "Qnot-a-number").isEmpty())
    }

    @Test fun underscoreNormalizationIsForMatchingOnlyNeverForTrustingAName() {
        assertEquals(setOf("Actual_artist.jpg"), CommonsDepictionBatchPolicy.verifiedFileTitles(
            listOf("Actual_artist.jpg"),
            listOf(CommonsDepictionBatchPolicy.Page("File:Actual artist.jpg", "42")),
            mapOf("M42" to setOf(qid)), qid))
    }
}
