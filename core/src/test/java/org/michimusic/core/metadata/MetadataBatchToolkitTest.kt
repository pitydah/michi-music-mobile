package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataBatchToolkitTest {
    private fun snap(
        id: String = "1", source: String = "A", verified: Boolean = true,
        fields: Map<MetadataField, List<String>> = mapOf(
            MetadataField.TITLE to listOf("  The Song  "),
            MetadataField.ARTIST to listOf("The Artist"),
            MetadataField.GENRE to listOf("Jazz; Fusion"),
        ),
    ) = MetadataSnapshot(
        MetadataTarget(source, id, "content://test/$source/$id", "stamp-$id"),
        verified, fields, mapOf("TXXX:KEEP" to listOf("raw, unchanged")),
        verifiedFields = MetadataField.entries.toSet())

    private fun ready(vararg actions: MetadataBatchAction, snapshots: List<MetadataSnapshot> = listOf(snap())):
        MetadataBatchResult.Ready = MetadataBatchToolkit.review(
            snapshots.map { MetadataBatchEntry(it) }, actions.toList()) as MetadataBatchResult.Ready

    @Test fun copyMissingArtistIsConservativeAndDoesNotClobber() {
        val album = snap(fields=mapOf(MetadataField.ARTIST to listOf("A"),
            MetadataField.ALBUM_ARTIST to listOf("Another Artist")))
        val result = ready(MetadataBatchAction.Copy(MetadataField.ARTIST, MetadataField.ALBUM_ARTIST), snapshots=listOf(album))
        assertEquals(1, result.unchanged)
        val missing = snap(fields=mapOf(MetadataField.ARTIST to listOf("A")))
        val changed = ready(MetadataBatchAction.Copy(MetadataField.ARTIST, MetadataField.ALBUM_ARTIST), snapshots=listOf(missing))
        assertEquals(listOf("A"), (changed.entries.single() as MetadataReview.Ready)
            .proposed.known[MetadataField.ALBUM_ARTIST])
    }

    @Test fun copiesMultivaluedFieldWithoutSplitting() {
        val original = snap(fields=mapOf(MetadataField.GENRE to listOf("Jazz; Fusion", "Rock")))
        val res = ready(MetadataBatchAction.Copy(MetadataField.GENRE, MetadataField.ARTIST), snapshots=listOf(original))
        val out = (res.entries.single() as MetadataReview.Ready)
        assertEquals(listOf("Jazz; Fusion", "Rock"), out.proposed.known[MetadataField.ARTIST])
        assertEquals(original.opaque, out.proposed.opaque)
    }

    @Test fun appendsMultigenresWithoutMutation() {
        val original = snap()
        val res = ready(MetadataBatchAction.Append(MetadataField.GENRE, listOf("Bebop")))
        assertEquals(listOf("Jazz; Fusion", "Bebop"),
            (res.entries.single() as MetadataReview.Ready).proposed.known[MetadataField.GENRE])
        assertEquals(listOf("Jazz; Fusion"), original.known[MetadataField.GENRE])
    }

    @Test fun appendingToSingleValueRejected() {
        assertEquals(MetadataBatchError.INVALID_RECIPE, (MetadataBatchToolkit.review(
            listOf(MetadataBatchEntry(snap())),
            listOf(MetadataBatchAction.Append(MetadataField.TITLE, listOf("X")))) as MetadataBatchResult.Blocked).reason)
    }

    @Test fun replacesLiteralWithoutTouchingOtherValues() {
        val res = ready(MetadataBatchAction.FindReplace(MetadataField.GENRE, "Fusion", "Fusión"))
        val result = res.entries.single() as MetadataReview.Ready
        assertEquals(listOf("Jazz; Fusión"), result.proposed.known[MetadataField.GENRE])
        assertEquals(listOf("Jazz; Fusion"), result.original.known[MetadataField.GENRE])
    }

    @Test fun emptySearchRejected() {
        assertEquals(MetadataBatchError.INVALID_FIND, (MetadataBatchToolkit.review(
            listOf(MetadataBatchEntry(snap())),
            listOf(MetadataBatchAction.FindReplace(MetadataField.TITLE, "", "X")))
            as MetadataBatchResult.Blocked).reason)
    }

    @Test fun trimmingIsExplicitAndNotAutomatic() {
        val res = ready(MetadataBatchAction.Trim(MetadataField.TITLE))
        assertEquals(listOf("The Song"), (res.entries.single() as MetadataReview.Ready)
            .proposed.known[MetadataField.TITLE])
        assertEquals("  The Song  ", snap().known[MetadataField.TITLE]?.single())
    }

    @Test fun rootLocaleForCaseAndNoImplicitTitleCase() {
        val res = ready(MetadataBatchAction.ChangeCase(MetadataField.TITLE, CaseMode.UPPERCASE))
        assertEquals(listOf("  THE SONG  "), (res.entries.single() as MetadataReview.Ready)
            .proposed.known[MetadataField.TITLE])
    }

    @Test fun sequenceUsesSelectedOrderOnly() {
        val original = listOf(snap("a"),snap("b"),snap("c"))
        val res=ready(MetadataBatchAction.TrackSequence(first=4, padding=2), snapshots=original)
        assertEquals(listOf("04","05","06"),res.entries.map {
            (it as MetadataReview.Ready).proposed.known[MetadataField.TRACK_NUMBER]!!.single() })
    }

    @Test fun overflowNumberingRejectedBeforeWriting() {
        val result = MetadataBatchToolkit.review(listOf(MetadataBatchEntry(snap("1")), MetadataBatchEntry(snap("2"))),
            listOf(MetadataBatchAction.TrackSequence(first=9999)))
        assertEquals(MetadataBatchError.INVALID_NUMBERING, (result as MetadataBatchResult.Blocked).reason)
    }

    @Test fun sequentialRulesResultInOneNetDiff() {
        val res=ready(MetadataBatchAction.Trim(MetadataField.TITLE),
            MetadataBatchAction.FindReplace(MetadataField.TITLE, "Song", "Piece"))
        val diff=(res.entries.single() as MetadataReview.Ready).diffs.single()
        assertEquals(listOf("  The Song  "),diff.before)
        assertEquals(listOf("The Piece"),diff.after)
    }

    @Test fun anUndoingRuleProducesUnchanged() {
        val result=ready(MetadataBatchAction.Trim(MetadataField.TITLE),
            MetadataBatchAction.Set(MetadataChange.Replace(MetadataField.TITLE, listOf("  The Song  "))))
        assertEquals(1,result.unchanged)
    }

    @Test fun explicitClearVsKeep() {
        assertEquals(1,ready().unchanged)
        val deleted=ready(MetadataBatchAction.Set(MetadataChange.Clear(MetadataField.GENRE)))
        assertTrue(MetadataField.GENRE !in (deleted.entries.single() as MetadataReview.Ready).proposed.known)
    }

    @Test fun unverifiedAndMixedOwnersFailClosed() {
        val no=MetadataBatchToolkit.review(listOf(MetadataBatchEntry(snap(verified=false))), emptyList())
        assertEquals(MetadataBlockReason.UNVERIFIED_EMBEDDED_TAGS,(no as MetadataBatchResult.Blocked).validation)
        val mixed=MetadataBatchToolkit.review(listOf(MetadataBatchEntry(snap()),MetadataBatchEntry(snap("2", source="B"))), emptyList())
        assertEquals(MetadataBlockReason.MIXED_SOURCES,(mixed as MetadataBatchResult.Blocked).validation)
    }

    @Test fun filenameImportAddsOnlyExplicitFields() {
        val snapshot = snap(fields=mapOf(MetadataField.GENRE to listOf("R&B/Soul")))
        val res = MetadataBatchToolkit.review(listOf(MetadataBatchEntry(snapshot,"03 - Artist - Time.flac")),
            listOf(MetadataBatchAction.ImportFilename("%track% - %artist% - %title%"))) as MetadataBatchResult.Ready
        val out=(res.entries.single() as MetadataReview.Ready).proposed.known
        assertEquals(listOf("03"),out[MetadataField.TRACK_NUMBER])
        assertEquals(listOf("Artist"),out[MetadataField.ARTIST])
        assertEquals(listOf("Time"),out[MetadataField.TITLE])
        assertEquals(listOf("R&B/Soul"),out[MetadataField.GENRE])
    }

    @Test fun missingFilenameIsReportedPerIndex() {
        val res=MetadataBatchToolkit.review(listOf(MetadataBatchEntry(snap())),
            listOf(MetadataBatchAction.ImportFilename("%title%"))) as MetadataBatchResult.Blocked
        assertEquals(MetadataBatchError.MISSING_FILENAME,res.reason)
        assertEquals(0,res.index)
    }

    @Test fun trimToBlankBlockedWithNoPartialResult() {
        val bad=snap(fields=mapOf(MetadataField.TITLE to listOf("   ")))
        val res=MetadataBatchToolkit.review(listOf(MetadataBatchEntry(bad)),
            listOf(MetadataBatchAction.Trim(MetadataField.TITLE))) as MetadataBatchResult.Blocked
        assertEquals(MetadataBlockReason.EMPTY_VALUE,res.validation)
    }

    @Test fun recipeCatalogDoesNotClearExistingAlbumArtist() {
        val already=snap(fields=mapOf(MetadataField.ARTIST to listOf("A"), MetadataField.ALBUM_ARTIST to listOf("B")))
        val result=MetadataBatchToolkit.review(listOf(MetadataBatchEntry(already)),
            MetadataRecipeCatalog.fillMissingAlbumArtist.actions) as MetadataBatchResult.Ready
        assertEquals(1,result.unchanged)
        assertTrue(MetadataRecipeCatalog.clearComments.destructive)
    }

    @Test fun copyIntoUnverifiedMissingFieldNeverAssumesAbsence() {
        val partial = snap(fields = mapOf(MetadataField.ARTIST to listOf("A")))
            .copy(verifiedFields = setOf(MetadataField.ARTIST))
        val result = MetadataBatchToolkit.review(listOf(MetadataBatchEntry(partial)),
            listOf(MetadataBatchAction.Copy(MetadataField.ARTIST, MetadataField.ALBUM_ARTIST)))
        assertEquals(MetadataBlockReason.FIELD_NOT_VERIFIED,
            (result as MetadataBatchResult.Blocked).validation)
    }


    @Test fun removeExactDeletesOnlyOneDuplicateOccurrence() {
        val source = snap(fields = mapOf(MetadataField.GENRE to listOf("Jazz", "Rock", "Jazz")))
        val result = ready(MetadataBatchAction.RemoveExact(MetadataField.GENRE, 2, "Jazz"), snapshots=listOf(source))
        val changed = result.entries.single() as MetadataReview.Ready
        assertEquals(listOf("Jazz", "Rock"), changed.proposed.known[MetadataField.GENRE])
        assertEquals(listOf("Jazz", "Rock", "Jazz"), source.known[MetadataField.GENRE])
        assertEquals(source.opaque, changed.proposed.opaque)
    }

    @Test fun removeExactLastOccurrenceUsesExplicitClear() {
        val source = snap(fields=mapOf(MetadataField.GENRE to listOf("Rock")))
        val result=ready(MetadataBatchAction.RemoveExact(MetadataField.GENRE,0,"Rock"),snapshots=listOf(source))
        val changed=result.entries.single() as MetadataReview.Ready
        assertTrue(MetadataField.GENRE !in changed.proposed.known)
        assertEquals(listOf("Rock"),changed.diffs.single().before)
        assertEquals(emptyList<String>(),changed.diffs.single().after)
    }

    @Test fun removeExactRejectsMismatchedPositionWithoutPartialBatch() {
        val a=snap("a", fields=mapOf(MetadataField.GENRE to listOf("A","B")))
        val b=snap("b", fields=mapOf(MetadataField.GENRE to listOf("B","A")))
        val result=MetadataBatchToolkit.review(listOf(MetadataBatchEntry(a),MetadataBatchEntry(b)),
            listOf(MetadataBatchAction.RemoveExact(MetadataField.GENRE,0,"A"))) as MetadataBatchResult.Blocked
        assertEquals(MetadataBatchError.INVALID_REMOVAL,result.reason)
        assertEquals(1,result.index)
    }

    @Test fun removeExactRejectsOutOfRangeAndSingleValuedFields() {
        val invalid = MetadataBatchToolkit.review(listOf(MetadataBatchEntry(snap())),
            listOf(MetadataBatchAction.RemoveExact(MetadataField.GENRE,5,"Jazz; Fusion")))
        assertEquals(MetadataBatchError.INVALID_REMOVAL,(invalid as MetadataBatchResult.Blocked).reason)
        val single = MetadataBatchToolkit.review(listOf(MetadataBatchEntry(snap())),
            listOf(MetadataBatchAction.RemoveExact(MetadataField.TITLE,0,"  The Song  ")))
        assertEquals(MetadataBatchError.INVALID_REMOVAL,(single as MetadataBatchResult.Blocked).reason)
    }

    @Test fun removeExactNeverReadsAnUnverifiedFieldAsEmpty() {
        val source=snap(fields=mapOf(MetadataField.TITLE to listOf("Song")))
            .copy(verifiedFields=setOf(MetadataField.TITLE))
        val result=MetadataBatchToolkit.review(listOf(MetadataBatchEntry(source)),
            listOf(MetadataBatchAction.RemoveExact(MetadataField.GENRE,0,"Jazz")))
            as MetadataBatchResult.Blocked
        assertEquals(MetadataBlockReason.FIELD_NOT_VERIFIED,result.validation)
    }

    @Test fun removeExactRechecksPositionAfterEarlierQueuedStep() {
        val source=snap(fields=mapOf(MetadataField.GENRE to listOf("Jazz","Rock")))
        val result=MetadataBatchToolkit.review(listOf(MetadataBatchEntry(source)),listOf(
            MetadataBatchAction.Set(MetadataChange.Replace(MetadataField.GENRE,listOf("Rock","Jazz"))),
            MetadataBatchAction.RemoveExact(MetadataField.GENRE,0,"Jazz")))
        assertEquals(MetadataBatchError.INVALID_REMOVAL,(result as MetadataBatchResult.Blocked).reason)
    }

}
