package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataStructuredGenreRefreshPolicyTest {
    private val required = MetadataStructuredGenreRefreshPolicy.requiredEvidenceFields
    private fun delta(genres: List<String>,
        changed: Map<MetadataField,List<String>> = emptyMap(),
        unknown: Set<MetadataField> = emptySet()
    ): MetadataSourceIndexDelta.Delta = MetadataSourceIndexDelta.Delta(
        MetadataTarget("", "song", "content://media/external/audio/media/12", "verified"),
        "tx", "a".repeat(64),
        required.associateWith { emptyList<String>() } +
            mapOf(MetadataField.GENRE to genres) + changed,
        unknown, 4096L)

    @Test fun orderedDuplicatesRemainDistinguishable() {
        val result = MetadataStructuredGenreRefreshPolicy.prepare(
            delta(listOf("Jazz", "Jazz", "Fusion")))
        assertTrue(result is MetadataStructuredGenreRefreshPolicy.Result.Prepared)
        val prepared = result as MetadataStructuredGenreRefreshPolicy.Result.Prepared
        assertEquals("Jazz", prepared.primaryGenre)
        assertEquals(listOf("Jazz", "Jazz", "Fusion"),prepared.values)
        assertEquals("Jazz\u0000Jazz\u0000Fusion",prepared.genreValues)
        assertFalse(prepared.mayPublishNow)
    }
    @Test fun literalSemicolonIsNeverSplit() {
        val result = MetadataStructuredGenreRefreshPolicy.prepare(
            delta(listOf("Jazz; Fusion", "Soul"))) as MetadataStructuredGenreRefreshPolicy.Result.Prepared
        assertEquals("Jazz; Fusion\u0000Soul",result.genreValues)
    }
    @Test fun singleGenreUsesExistingScalarPath() {
        assertEquals(MetadataStructuredGenreRefreshPolicy.Reason.NOT_A_GENRE_ONLY_REFRESH,
            (MetadataStructuredGenreRefreshPolicy.prepare(delta(listOf("Jazz")))
                as MetadataStructuredGenreRefreshPolicy.Result.Blocked).reason)
    }
    @Test fun unknownIndexedFieldCannotBeInvented() {
        assertEquals(MetadataStructuredGenreRefreshPolicy.Reason.INDEX_FIELDS_NOT_VERIFIED,
            (MetadataStructuredGenreRefreshPolicy.prepare(delta(listOf("Jazz","Fusion"),
                unknown=setOf(MetadataField.COMPOSER)))
                as MetadataStructuredGenreRefreshPolicy.Result.Blocked).reason)
    }
    @Test fun secondMultivalueArtistIsRejected() {
        assertEquals(MetadataStructuredGenreRefreshPolicy.Reason.NOT_A_GENRE_ONLY_REFRESH,
            (MetadataStructuredGenreRefreshPolicy.prepare(delta(listOf("Jazz","Fusion"),
                changed=mapOf(MetadataField.ARTIST to listOf("A","B"))))
                as MetadataStructuredGenreRefreshPolicy.Result.Blocked).reason)
    }
    @Test fun embeddedNulIsRejected() {
        assertEquals(MetadataStructuredGenreRefreshPolicy.Reason.INVALID_GENRE_VALUE,
            (MetadataStructuredGenreRefreshPolicy.prepare(delta(listOf("Jazz\u0000Fusion", "Soul")))
                as MetadataStructuredGenreRefreshPolicy.Result.Blocked).reason)
    }
    @Test fun missingEvidenceFieldFailsClosed() {
        val original=delta(listOf("Jazz","Fusion"))
        val missing=original.copy(observedValues=original.observedValues-
            MetadataField.TRACK_TOTAL)
        assertEquals(MetadataStructuredGenreRefreshPolicy.Reason.INDEX_FIELDS_NOT_VERIFIED,
            (MetadataStructuredGenreRefreshPolicy.prepare(missing)
                as MetadataStructuredGenreRefreshPolicy.Result.Blocked).reason)
    }
    @Test fun noGenresAndTooManyGenresFailClosed() {
        for(values in listOf(emptyList(),List(33){"Genre$it"})) {
            assertTrue(MetadataStructuredGenreRefreshPolicy.prepare(delta(values))
                is MetadataStructuredGenreRefreshPolicy.Result.Blocked)
        }
    }
    @Test fun unknownSourceCannotBeTreatedAsVerified() {
        val invalid=delta(listOf("Jazz","Fusion")).copy(sourceSha256="")
        assertTrue(MetadataStructuredGenreRefreshPolicy.prepare(invalid)
            is MetadataStructuredGenreRefreshPolicy.Result.Blocked)
    }
}
