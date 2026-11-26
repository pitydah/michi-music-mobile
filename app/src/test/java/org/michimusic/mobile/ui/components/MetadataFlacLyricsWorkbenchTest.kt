package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataEditorDraftSession
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataFieldObservation
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget

/**
 * A09: FLAC lyrics are a scalar Vorbis draft, never an ID3 COMM/USLT resource.
 * The production FLAC writer is already certified separately; this test covers
 * the Workbench draft -> review -> save-bundle contract.
 */
class MetadataFlacLyricsWorkbenchTest {
    private fun snapshot(lyrics: List<String>?) = MetadataSnapshot(
        target = MetadataTarget(
            sourceId = "lib_device_local",
            trackId = "flac_track",
            uri = "content://media/external/audio/media/42",
            evidenceToken = "me19:flac-lyrics",
        ),
        tagsVerified = true,
        known = if (lyrics == null) emptyMap()
        else mapOf(MetadataField.LYRICS to lyrics),
        verifiedFields = setOf(MetadataField.LYRICS),
        fieldObservations = mapOf(
            MetadataField.LYRICS to if (lyrics == null)
                MetadataFieldObservation.VerifiedAbsent
            else MetadataFieldObservation.Present(lyrics),
        ),
    )

    private fun bundle(review: MetadataReview): MetadataWorkbenchSaveBundlePolicy.Result.Ready {
        val result = MetadataWorkbenchSaveBundlePolicy.build(
            review,
            MetadataArtLyricsUiState(
                load = MetadataArtLyricsUiState.Load.NotRequested,
                operations = emptyMap(),
                artwork = null,
                artworkSource = null,
                artworkEdit = null,
                selectedOrdinal = null,
            ),
            0,
        )
        assertTrue("bundle=$result", result is MetadataWorkbenchSaveBundlePolicy.Result.Ready)
        return result as MetadataWorkbenchSaveBundlePolicy.Result.Ready
    }

    @Test fun flacLyricsUseScalarDraftAndClear() {
        val added = MetadataEditorDraftSession.start(snapshot(null))
            .type(MetadataField.LYRICS, listOf("Letra añadida"))
        assertTrue(added.isDirty)
        assertTrue(MetadataField.LYRICS in added.editedFields)
        val addedReview = added.review
        assertTrue("add review=$addedReview", addedReview is MetadataReview.Ready)
        val addedBundle = bundle(addedReview)
        val addedDifferences = addedBundle.scalarReview!!.diffs
        assertEquals(listOf(MetadataField.LYRICS), addedDifferences.map { it.field })
        assertEquals(emptyList<String>(), addedDifferences.single().before)
        assertEquals(listOf("Letra añadida"), addedDifferences.single().after)
        assertTrue("FLAC lyrics must not become COMM/USLT resources",
            addedBundle.resourceEdits.isEmpty())

        val cleared = MetadataEditorDraftSession.start(snapshot(listOf("Original")))
            .clear(MetadataField.LYRICS)
        assertTrue(cleared.isDirty)
        val clearedReview = cleared.review
        assertTrue("clear review=$clearedReview", clearedReview is MetadataReview.Ready)
        val clearedBundle = bundle(clearedReview)
        val clearedDifferences = clearedBundle.scalarReview!!.diffs
        assertEquals(listOf(MetadataField.LYRICS), clearedDifferences.map { it.field })
        assertEquals(listOf("Original"), clearedDifferences.single().before)
        assertTrue(clearedDifferences.single().after.isEmpty())
        assertTrue("FLAC lyric clear must stay scalar",
            clearedBundle.resourceEdits.isEmpty())
        assertFalse(clearedBundle.resourceEdits.isNotEmpty())
    }
}
