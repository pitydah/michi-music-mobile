package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataScalarIndexRefreshPolicyTest {
    private fun delta(values:Map<MetadataField,List<String>>,
        unknown:Set<MetadataField> = emptySet()):MetadataSourceIndexDelta.Delta {
        val target=MetadataTarget("","track","content://media/track","cold-token")
        return MetadataSourceIndexDelta.Delta(target,"tx","a".repeat(64),values,unknown,123L)
    }
    @Test fun singleValuedFieldsCanProceedToCas() {
        val actual=MetadataScalarIndexRefreshPolicy.evaluate(delta(mapOf(
            MetadataField.TITLE to listOf("A"),
            MetadataField.ARTIST to listOf("B"),
            MetadataField.GENRE to listOf("Jazz; Fusion"))))
        assertEquals(MetadataScalarIndexRefreshPolicy.Verdict.MayTryRoomCas,actual)
    }
    @Test fun twoGenreValuesRequireOnDemandRefreshNotFlattening() {
        val actual=MetadataScalarIndexRefreshPolicy.evaluate(delta(mapOf(
            MetadataField.GENRE to listOf("Jazz","Fusion"))))
        assertTrue(actual is MetadataScalarIndexRefreshPolicy.Verdict.SelectiveRefreshRequired)
        assertFalse((actual as MetadataScalarIndexRefreshPolicy.Verdict.SelectiveRefreshRequired).canPublishSaved)
        assertTrue(MetadataField.GENRE in actual.fields)
    }
    @Test fun duplicateArtistValuesCannotBeFlattened() {
        val actual=MetadataScalarIndexRefreshPolicy.evaluate(delta(mapOf(
            MetadataField.ARTIST to listOf("A","A"))))
        assertEquals(MetadataScalarIndexRefreshPolicy.Reason.AMBIGUOUS_MULTIVALUE,
            (actual as MetadataScalarIndexRefreshPolicy.Verdict.SelectiveRefreshRequired).reason)
    }
    @Test fun unverifiedIndexedFieldBlocksCas() {
        val actual=MetadataScalarIndexRefreshPolicy.evaluate(delta(emptyMap(),
            setOf(MetadataField.TITLE)))
        assertEquals(MetadataScalarIndexRefreshPolicy.Reason.UNVERIFIABLE_INDEX_FIELD,
            (actual as MetadataScalarIndexRefreshPolicy.Verdict.SelectiveRefreshRequired).reason)
    }
    @Test fun exactYearDateMayProceed() {
        assertEquals(MetadataScalarIndexRefreshPolicy.Verdict.MayTryRoomCas,
            MetadataScalarIndexRefreshPolicy.evaluate(delta(mapOf(
                MetadataField.RELEASE_DATE to listOf("2020-07-09")))))
    }
    @Test fun arbitraryYearTextRequiresRefresh() {
        val actual=MetadataScalarIndexRefreshPolicy.evaluate(delta(mapOf(
            MetadataField.RELEASE_DATE to listOf("c. 1970"))))
        assertEquals(MetadataScalarIndexRefreshPolicy.Reason.PARTIAL_OR_UNSUPPORTED_DATE,
            (actual as MetadataScalarIndexRefreshPolicy.Verdict.SelectiveRefreshRequired).reason)
    }
    @Test fun invalidTrackNumberIsNotPublished() {
        val actual=MetadataScalarIndexRefreshPolicy.evaluate(delta(mapOf(
            MetadataField.TRACK_NUMBER to listOf("10000"))))
        assertEquals(MetadataScalarIndexRefreshPolicy.Reason.INVALID_SCALAR_NUMBER,
            (actual as MetadataScalarIndexRefreshPolicy.Verdict.SelectiveRefreshRequired).reason)
    }
}
