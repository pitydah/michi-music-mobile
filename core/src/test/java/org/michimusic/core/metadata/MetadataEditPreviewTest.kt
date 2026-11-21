package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataEditPreviewTest {
    private fun snapshot(
        source: String = "A",
        track: String = "t1",
        verified: Boolean = true,
        known: Map<MetadataField, List<String>> = mapOf(
            MetadataField.GENRE to listOf("Jazz; Fusion"),
            MetadataField.ARTIST to listOf("Artist"),
        ),
    ) = MetadataSnapshot(
        target = MetadataTarget(source, track, "content://test/$track", "verified-fingerprint"),
        tagsVerified = verified,
        known = known,
        opaque = mapOf("ID3:PRIVATE" to listOf("untouched")),
        verifiedFields = MetadataField.entries.toSet(),
    )

    @Test fun preservesLiteralSemicolonAndUnknownTags() {
        val original = snapshot()
        val result = MetadataEditPreview.review(original, listOf(
            MetadataChange.Replace(MetadataField.GENRE, listOf("Jazz", "Fusion"))
        )) as MetadataReview.Ready
        assertEquals(listOf("Jazz; Fusion"), result.diffs.single().before)
        assertEquals(listOf("Jazz", "Fusion"), result.diffs.single().after)
        assertEquals(original.opaque, result.proposed.opaque)
        assertEquals(original.known[MetadataField.ARTIST], result.proposed.known[MetadataField.ARTIST])
    }

    @Test fun keepByOmissionAndExplicitClearAreDifferent() {
        val unchanged = MetadataEditPreview.review(snapshot(), emptyList())
        assertEquals(MetadataReview.Unchanged, unchanged)
        val clear = MetadataEditPreview.review(snapshot(), listOf(MetadataChange.Clear(MetadataField.GENRE)))
            as MetadataReview.Ready
        assertTrue(MetadataField.GENRE !in clear.proposed.known)
    }

    @Test fun unverifiedFileMustNeverProduceReady() {
        assertEquals(MetadataReview.Blocked(MetadataBlockReason.UNVERIFIED_EMBEDDED_TAGS),
            MetadataEditPreview.review(snapshot(verified = false), listOf(
                MetadataChange.Clear(MetadataField.GENRE))))
    }

    @Test fun duplicateChangesAreBlocked() {
        val changes = listOf(MetadataChange.Clear(MetadataField.GENRE),
            MetadataChange.Replace(MetadataField.GENRE, listOf("Rock")))
        assertEquals(MetadataReview.Blocked(MetadataBlockReason.DUPLICATE_FIELD_OPERATION),
            MetadataEditPreview.review(snapshot(), changes))
    }

    @Test fun singleValueFieldRefusesMultiple() {
        val change = MetadataChange.Replace(MetadataField.TITLE, listOf("one", "two"))
        assertEquals(MetadataReview.Blocked(MetadataBlockReason.MULTIPLE_VALUES_UNSUPPORTED),
            MetadataEditPreview.review(snapshot(), listOf(change)))
    }

    @Test fun emptyAndNulValuesAreRejected() {
        assertEquals(MetadataReview.Blocked(MetadataBlockReason.EMPTY_VALUE),
            MetadataEditPreview.review(snapshot(), listOf(
                MetadataChange.Replace(MetadataField.ARTIST, listOf("  ")))))
        assertEquals(MetadataReview.Blocked(MetadataBlockReason.NUL_CHARACTER),
            MetadataEditPreview.review(snapshot(), listOf(
                MetadataChange.Replace(MetadataField.GENRE, listOf("Jazz\u0000Fusion")))))
    }

    @Test fun mixedSelectionCannotBecomeImplicitOverwrite() {
        val result = MetadataEditPreview.selectionValue(listOf(snapshot(),
            snapshot(track = "t2", known = mapOf(MetadataField.GENRE to listOf("Rock")))),
            MetadataField.GENRE)
        assertEquals(MetadataEditPreview.SelectionValue.Mixed, result)
    }

    @Test fun batchBlocksCrossOwnerAndDuplicateSelection() {
        val edit = listOf(MetadataChange.Clear(MetadataField.GENRE))
        assertEquals(MetadataEditPreview.BatchReview.Blocked(MetadataBlockReason.MIXED_SOURCES),
            MetadataEditPreview.reviewBatch(listOf(snapshot("A"), snapshot("B", "t2")), edit))
        assertEquals(MetadataEditPreview.BatchReview.Blocked(MetadataBlockReason.DUPLICATE_TARGET),
            MetadataEditPreview.reviewBatch(listOf(snapshot(), snapshot()), edit))
    }

    @Test fun batchRejectsUnverifiedWithoutPartialWritePlan() {
        val preview = MetadataEditPreview.reviewBatch(
            listOf(snapshot(), snapshot(track = "t2", verified = false)),
            listOf(MetadataChange.Clear(MetadataField.GENRE)))
        assertEquals(MetadataEditPreview.BatchReview.Blocked(MetadataBlockReason.UNVERIFIED_EMBEDDED_TAGS), preview)
    }

    @Test fun changesDoNotMutateOriginalAndKeepOriginalOrder() {
        val values = mutableListOf("Soul", "Funk")
        val change = MetadataChange.Replace(MetadataField.GENRE, values)
        val original = snapshot()
        val preview = MetadataEditPreview.review(original, listOf(change)) as MetadataReview.Ready
        values.add("Jazz")
        assertEquals(listOf("Soul", "Funk"), preview.proposed.known[MetadataField.GENRE])
        assertEquals(listOf("Jazz; Fusion"), original.known[MetadataField.GENRE])
    }

    @Test fun localSourceIdCanBeBlankAndStillBeOwnerScoped() {
        val local = MetadataTarget("", "local-track", "content://media/audio/5", "cold-token")
        assertEquals("", local.sourceId)
        assertEquals("local-track", local.trackId)
    }

    @Test fun parsedTagDoesNotProveUnsupportedFieldsAreEmpty() {
        val partial = snapshot().copy(verifiedFields = setOf(MetadataField.GENRE))
        assertEquals(MetadataReview.Blocked(MetadataBlockReason.FIELD_NOT_VERIFIED),
            MetadataEditPreview.review(partial, listOf(MetadataChange.Clear(MetadataField.COMMENT))))
        assertEquals(MetadataEditPreview.SelectionValue.Unverified,
            MetadataEditPreview.selectionValue(listOf(partial), MetadataField.COMMENT))
    }

    @Test fun explicitlyVerifiedEmptyGenreCanBeFilled() {
        val parsed = snapshot(known = emptyMap()).copy(verifiedFields = setOf(MetadataField.GENRE))
        val result = MetadataEditPreview.review(parsed,
            listOf(MetadataChange.Replace(MetadataField.GENRE, listOf("Jazz; Fusion"))))
        assertEquals(listOf("Jazz; Fusion"), (result as MetadataReview.Ready)
            .proposed.known[MetadataField.GENRE])
    }

}
