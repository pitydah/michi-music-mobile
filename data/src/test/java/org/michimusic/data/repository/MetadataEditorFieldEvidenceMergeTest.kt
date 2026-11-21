package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataFieldReadReport
import org.michimusic.core.metadata.MetadataPhysicalContainer
import org.michimusic.core.metadata.MetadataObservationIssue

class MetadataEditorFieldEvidenceMergeTest {
    private fun report(vararg parts: Pair<MetadataField, MetadataFieldObservation>) =
        MetadataFieldReadReport(MetadataPhysicalContainer.FLAC, 1, mapOf(*parts))

    @Test fun strictAbsenceIsVerifiableOnlyWhenLegacyHasNoValue() {
        val r=MetadataEditorFieldEvidenceMerge.merge(emptyMap(), report(
            MetadataField.TITLE to MetadataFieldObservation.VerifiedAbsent))
        assertTrue(MetadataField.TITLE in r.verified)
        assertFalse(r.known.containsKey(MetadataField.TITLE))
    }
    @Test fun legacyPresentVersusStrictAbsenceBlocks() {
        val r=MetadataEditorFieldEvidenceMerge.merge(mapOf(MetadataField.TITLE to listOf("Old")),report(
            MetadataField.TITLE to MetadataFieldObservation.VerifiedAbsent))
        assertTrue(r.observations[MetadataField.TITLE] is MetadataFieldObservation.Conflicted)
        assertFalse(MetadataField.TITLE in r.verified)
    }
    @Test fun strictMultiGenrePreservesLiteralSeparators() {
        val values=listOf("Jazz; Fusion","Soul")
        val r=MetadataEditorFieldEvidenceMerge.merge(mapOf(MetadataField.GENRE to values), report(
            MetadataField.GENRE to MetadataFieldObservation.Present(values)))
        assertEquals(values,r.known[MetadataField.GENRE])
        assertTrue(MetadataField.GENRE in r.verified)
    }
    @Test fun contradictoryGenreDecodersFailClosed() {
        val r=MetadataEditorFieldEvidenceMerge.merge(mapOf(MetadataField.GENRE to listOf("Rock")),report(
            MetadataField.GENRE to MetadataFieldObservation.Present(listOf("(17)"))))
        assertFalse(MetadataField.GENRE in r.verified)
        assertFalse(MetadataField.GENRE in r.known)
    }
    @Test fun unverifiedFieldNeverBecomesVerifiedViaLegacy() {
        val r=MetadataEditorFieldEvidenceMerge.merge(mapOf(MetadataField.TITLE to listOf("A")),report(
            MetadataField.TITLE to MetadataFieldObservation.Unverifiable(
                MetadataObservationIssue.UNSUPPORTED_FLAGS)))
        assertFalse(MetadataField.TITLE in r.verified)
        assertFalse(MetadataField.TITLE in r.known)
    }
    @Test fun pipeFallbackOnlyTrustsPositiveLegacyEvidenceAndGenreAbsence() {
        val r=MetadataEditorFieldEvidenceMerge.merge(mapOf(MetadataField.TITLE to listOf("A")),null)
        assertTrue(MetadataField.TITLE in r.verified)
        assertTrue(MetadataField.GENRE in r.verified)
        assertFalse(MetadataField.ALBUM in r.verified)
    }
    @Test fun strictNewFieldCanBeAddedWhenLegacyDecoderSkippedIt() {
        val r=MetadataEditorFieldEvidenceMerge.merge(emptyMap(),report(
            MetadataField.ARTIST to MetadataFieldObservation.Present(listOf("A","B"))))
        assertEquals(listOf("A","B"),r.known[MetadataField.ARTIST])
        assertTrue(MetadataField.ARTIST in r.verified)
    }
    @Test fun duplicateAliasStaysConflicted() {
        val r=MetadataEditorFieldEvidenceMerge.merge(mapOf(MetadataField.ALBUM_ARTIST to listOf("A")),report(
            MetadataField.ALBUM_ARTIST to MetadataFieldObservation.Conflicted(
                MetadataObservationIssue.AMBIGUOUS_ALIAS)))
        assertFalse(MetadataField.ALBUM_ARTIST in r.verified)
    }
}
