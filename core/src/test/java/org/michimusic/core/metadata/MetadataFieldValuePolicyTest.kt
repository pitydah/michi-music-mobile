package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataFieldValuePolicyTest {
    @Test fun positiveTrackNumberAndZeroRejection() {
        assertNull(MetadataFieldValuePolicy.inspect(MetadataField.TRACK_NUMBER,listOf("1")))
        assertEquals(MetadataFieldValuePolicy.Failure.NUMBER_OUT_OF_RANGE,
            MetadataFieldValuePolicy.inspect(MetadataField.TRACK_NUMBER,listOf("001")))
        assertEquals(MetadataFieldValuePolicy.Failure.NUMBER_OUT_OF_RANGE,
            MetadataFieldValuePolicy.inspect(MetadataField.TRACK_NUMBER,listOf("0")))
    }
    @Test fun bpmMustBeFiniteDecimalWithinBounds() {
        assertNull(MetadataFieldValuePolicy.inspect(MetadataField.BPM,listOf("140.5")))
        assertEquals(MetadataFieldValuePolicy.Failure.INVALID_BPM,
            MetadataFieldValuePolicy.inspect(MetadataField.BPM,listOf("NaN")))
    }
    @Test fun malformedIsrcBlocks() {
        assertEquals(MetadataFieldValuePolicy.Failure.INVALID_ISRC,
            MetadataFieldValuePolicy.inspect(MetadataField.ISRC,listOf("ab123")))
        assertNull(MetadataFieldValuePolicy.inspect(MetadataField.ISRC,listOf("CLABC2600123")))
    }
    @Test fun malformedMusicBrainzIdBlocksBeforeStaging() {
        assertEquals(MetadataFieldValuePolicy.Failure.INVALID_MUSICBRAINZ_ID,
            MetadataFieldValuePolicy.inspect(MetadataField.MUSICBRAINZ_RECORDING_ID,listOf("not-a-uuid")))
    }
    @Test fun freeformGenreNeverReclassifiedOrNormalized() {
        assertNull(MetadataFieldValuePolicy.inspect(MetadataField.GENRE,listOf("Jazz; Fusion","R&B/Soul")))
    }
    @Test fun previewPropagatesTypedValidation() {
        val x=MetadataSnapshot(MetadataTarget("","one","content://media/1","fingerprint"),
            true,emptyMap(),verifiedFields=setOf(MetadataField.ISRC))
        assertEquals(MetadataReview.Blocked(MetadataBlockReason.INVALID_FORMAT_SPECIFIC_VALUE),
            MetadataEditPreview.review(x,listOf(
                MetadataChange.Replace(MetadataField.ISRC,listOf("WRONG")))))
    }
}
