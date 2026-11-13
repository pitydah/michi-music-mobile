package org.michimusic.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.data.cache.EmbeddedTagEvidence

class ColdMetadataTagEquivalenceTest {
    private val fields = EmbeddedTagFields(
        title = "Track", artist = "Artist", album = "Album", genres = listOf("Jazz; Fusion"),
        composer = "Composer A", date = "1987", trackNumber = 2, trackTotal = 11,
        discNumber = 1, discTotal = 2)
    private fun evidence() = TagEvidencePolicy.record("saf-a", "track", 100, 42, fields)
    @Test fun unchangedActualTagsAreTrusted() = assertTrue(
        ColdMetadataTagEquivalence.matches(evidence(), fields))
    @Test fun sameStampButDifferentComposerFailsClosed() = assertFalse(
        ColdMetadataTagEquivalence.matches(evidence(), fields.copy(composer = "Composer B")))
    @Test fun sameStampButDifferentGenreFailsClosed() = assertFalse(
        ColdMetadataTagEquivalence.matches(evidence(), fields.copy(genres = listOf("Jazz", "Fusion"))))
    @Test fun sameStampButDifferentTitleOrDateFailsClosed() {
        assertFalse(ColdMetadataTagEquivalence.matches(evidence(), fields.copy(title = "Replacement")))
        assertFalse(ColdMetadataTagEquivalence.matches(evidence(), fields.copy(date = "1991")))
    }
    @Test fun sameStampButAlteredTrackNumbersFailClosed() = assertFalse(
        ColdMetadataTagEquivalence.matches(evidence(), fields.copy(trackTotal = 12)))
    @Test fun structurallyEquivalentTagsPreserveLiteralSemicolon() = assertTrue(
        ColdMetadataTagEquivalence.matches(evidence(), fields.copy(genres = listOf("Jazz; Fusion"))))
}
