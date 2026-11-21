package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataBlockReason
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataFieldReadReport
import org.michimusic.core.metadata.MetadataPhysicalContainer
import org.michimusic.core.metadata.MetadataObservationIssue

/** OpenCode/Gradle execution required; only source staged in Drive. */
class MetadataEditorEvidenceMapperTest {
    private val stamp = RecoveryDocumentStamp(8912L, 1782800000L)

    @Test fun localLibraryOwnerBlankIsValidAndGenresRemainStructured() {
        val parsed = EmbeddedTagFields(title = "Time", genres = listOf("Jazz; Fusion", "Bebop"))
        val mapped = MetadataEditorEvidenceMapper.fromColdVerifiedRead(
            "", "local-7", "content://media/external/audio/media/7", stamp, parsed)
        assertEquals("", mapped.target.sourceId)
        assertEquals(listOf("Jazz; Fusion", "Bebop"), mapped.known[MetadataField.GENRE])
        assertTrue(MetadataField.TITLE in mapped.verifiedFields)
        assertFalse(MetadataField.LYRICS in mapped.verifiedFields)
        assertFalse(MetadataField.COMMENT in mapped.verifiedFields)
    }

    @Test fun omittedTitleIsUnverifiedNotProvenEmpty() {
        val mapped = MetadataEditorEvidenceMapper.fromColdVerifiedRead(
            "", "x", "content://test/a", stamp, EmbeddedTagFields(artist = "A"))
        assertFalse(MetadataField.TITLE in mapped.verifiedFields)
        assertTrue(MetadataField.GENRE in mapped.verifiedFields)
        assertEquals(MetadataReview.Blocked(MetadataBlockReason.FIELD_NOT_VERIFIED),
            MetadataEditPreview.review(mapped,
                listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("Guess")))))
    }

    @Test fun presentEmbeddedTitleCanBeProposedButNotWritten() {
        val mapped = MetadataEditorEvidenceMapper.fromColdVerifiedRead(
            "", "x", "content://test/a", stamp, EmbeddedTagFields(title = "Actual"))
        val proposed = MetadataEditPreview.review(mapped,
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("Proposal"))))
        assertEquals(listOf("Actual"), (proposed as MetadataReview.Ready).diffs.single().before)
        assertEquals(listOf("Proposal"), proposed.diffs.single().after)
    }

    @Test fun evidenceTokenDoesNotExposeUriOrArtistAndChangesWithStamp() {
        val data = EmbeddedTagFields(artist = "Secret Artist", genres = listOf("Jazz"))
        val uri = "content://provider/private/secret-song"
        val before = MetadataEditorEvidenceMapper.fromColdVerifiedRead("A", "b", uri, stamp, data)
        val after = MetadataEditorEvidenceMapper.fromColdVerifiedRead("A", "b", uri, stamp.copy(modifiedAt = 1782800001), data)
        assertFalse(before.target.evidenceToken.contains(uri))
        assertFalse(before.target.evidenceToken.contains("Secret"))
        assertNotEquals(before.target.evidenceToken, after.target.evidenceToken)
        assertEquals(before.target.evidenceToken,
            MetadataEditorEvidenceMapper.fromColdVerifiedRead("A", "b", uri, stamp, data).target.evidenceToken)
    }

    @Test fun provenEmptyTitleBecomesEditableAfterStrictRead() {
        val strict = MetadataFieldReadReport(MetadataPhysicalContainer.FLAC, 1,
            mapOf(MetadataField.TITLE to MetadataFieldObservation.VerifiedAbsent))
        val mapped = MetadataEditorEvidenceMapper.fromColdVerifiedRead("", "x", "content://test/a",
            stamp, EmbeddedTagFields(), strict)
        assertTrue(MetadataField.TITLE in mapped.verifiedFields)
        assertFalse(MetadataField.TITLE in mapped.known)
        val review=MetadataEditPreview.review(mapped, listOf(
            MetadataChange.Replace(MetadataField.TITLE, listOf("New title"))))
        assertTrue(review is MetadataReview.Ready)
    }

    @Test fun strictConflictsNeverAuthorizeEdit() {
        val strict = MetadataFieldReadReport(MetadataPhysicalContainer.ID3_V24, 1,
            mapOf(MetadataField.TITLE to MetadataFieldObservation.Conflicted(
                MetadataObservationIssue.DUPLICATE_FIELD)))
        val mapped = MetadataEditorEvidenceMapper.fromColdVerifiedRead("", "x", "content://test/a",
            stamp, EmbeddedTagFields(title="Old"), strict)
        assertFalse(MetadataField.TITLE in mapped.verifiedFields)
        assertEquals(MetadataReview.Blocked(MetadataBlockReason.FIELD_NOT_VERIFIED),
            MetadataEditPreview.review(mapped, listOf(MetadataChange.Clear(MetadataField.TITLE))))
    }

    @Test fun strictObservationReportChangesSessionToken() {
        val present=MetadataFieldReadReport(MetadataPhysicalContainer.FLAC,1,
            mapOf(MetadataField.TITLE to MetadataFieldObservation.Present(listOf("A"))))
        val absent=MetadataFieldReadReport(MetadataPhysicalContainer.FLAC,1,
            mapOf(MetadataField.TITLE to MetadataFieldObservation.VerifiedAbsent))
        val first=MetadataEditorEvidenceMapper.fromColdVerifiedRead("", "x", "content://test/a",
            stamp, EmbeddedTagFields(title="A"), present)
        val second=MetadataEditorEvidenceMapper.fromColdVerifiedRead("", "x", "content://test/a",
            stamp, EmbeddedTagFields(title="A"), absent)
        assertNotEquals(first.target.evidenceToken,second.target.evidenceToken)
    }

}
