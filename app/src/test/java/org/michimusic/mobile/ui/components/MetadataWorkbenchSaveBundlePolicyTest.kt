package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataArtworkLyricsProbe
import org.michimusic.core.metadata.MetadataArtworkReplacementPreview
import org.michimusic.core.metadata.MetadataDescribedTextWorkbench
import org.michimusic.core.metadata.MetadataId3DescribedTagCodec
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSelectiveAssetStage

class MetadataWorkbenchSaveBundlePolicyTest {
    private val png = byteArrayOf(
        0x89.toByte(), 80, 78, 71, 13, 10, 26, 10,
        0, 0, 0, 13, 73, 72, 68, 82,
        0, 0, 0, 1, 0, 0, 0, 1,
    )
    private val proposal = MetadataArtworkReplacementPreview.Proposal(
        index = 0,
        sourceSha256 = "a".repeat(64),
        proposedSha256 = "b".repeat(64),
        proposedMime = "image/png",
        proposedSize = png.size,
        proposedBytes = png,
    )

    @Test
    fun resourceOnlyExistingPictureReplacementIsSaveable() {
        val state = MetadataArtLyricsUiState(
            artwork = proposal,
            artworkEdit = ArtworkEdit.Replace(
                ordinal = 0,
                sourceSha256 = proposal.sourceSha256,
                bytes = png.copyOf(),
                mime = "image/png",
                widthPx = 1,
                heightPx = 1,
            ),
        )

        val result = MetadataWorkbenchSaveBundlePolicy.build(
            MetadataReview.Unchanged,
            state,
            provisionalCount = 0,
        ) as MetadataWorkbenchSaveBundlePolicy.Result.Ready

        assertNull(result.scalarReview)
        assertEquals(1, result.resourceEdits.size)
        assertTrue(result.resourceEdits.single() is MetadataSelectiveAssetStage.Edit.Picture)
    }

    @Test
    fun existingCommentReplacementAndLyricsClearMapExactly() {
        val comment = MetadataDescribedTextWorkbench.Identity(
            MetadataId3DescribedTagCodec.Kind.COMMENT,
            "spa",
            "principal",
        )
        val lyrics = MetadataDescribedTextWorkbench.Identity(
            MetadataId3DescribedTagCodec.Kind.LYRICS,
            "spa",
            "letra",
        )
        val replace = MetadataDescribedTextWorkbench.Change.Replace(comment, "Corregido")
        val clear = MetadataDescribedTextWorkbench.Change.Clear(lyrics)
        val snapshot = MetadataArtworkLyricsProbe.Snapshot(
            MetadataArtworkLyricsProbe.Format.ID3_V24,
            artworks = emptyList(),
            texts = listOf(
                MetadataDescribedTextWorkbench.Item(comment, "Anterior"),
                MetadataDescribedTextWorkbench.Item(lyrics, "Letra anterior"),
            ),
        )
        val state = MetadataArtLyricsUiState(
            load = MetadataArtLyricsUiState.Load.Ready(snapshot),
            operations = mapOf(lyrics to clear, comment to replace),
        )

        val result = MetadataWorkbenchSaveBundlePolicy.build(
            MetadataReview.Unchanged,
            state,
            provisionalCount = 0,
        ) as MetadataWorkbenchSaveBundlePolicy.Result.Ready

        assertEquals(
            listOf(replace, clear),
            result.resourceEdits.map {
                (it as MetadataSelectiveAssetStage.Edit.DescribedText).change
            },
        )
    }

    @Test
    fun artworkAddAndRemoveRemainTruthfullyBlocked() {
        val add = MetadataArtLyricsUiState(
            artworkEdit = ArtworkEdit.Add(png, "image/png", 1, 1),
        )
        val remove = MetadataArtLyricsUiState(
            artworkEdit = ArtworkEdit.Remove(0, "a".repeat(64)),
        )

        assertEquals(
            MetadataWorkbenchSaveBundlePolicy.Result.Blocked(
                MetadataWorkbenchSaveBundlePolicy.Reason.UNSUPPORTED_ARTWORK_ADD,
            ),
            MetadataWorkbenchSaveBundlePolicy.build(
                MetadataReview.Unchanged,
                add,
                provisionalCount = 0,
            ),
        )
        assertEquals(
            MetadataWorkbenchSaveBundlePolicy.Result.Blocked(
                MetadataWorkbenchSaveBundlePolicy.Reason.UNSUPPORTED_ARTWORK_REMOVE,
            ),
            MetadataWorkbenchSaveBundlePolicy.build(
                MetadataReview.Unchanged,
                remove,
                provisionalCount = 0,
            ),
        )
    }

    @Test
    fun unknownDescribedTextCannotBecomeAProductionEdit() {
        val identity = MetadataDescribedTextWorkbench.Identity(
            MetadataId3DescribedTagCodec.Kind.COMMENT,
            "spa",
            "nuevo",
        )
        val state = MetadataArtLyricsUiState(
            load = MetadataArtLyricsUiState.Load.Ready(
                MetadataArtworkLyricsProbe.Snapshot(
                    MetadataArtworkLyricsProbe.Format.ID3_V24,
                    emptyList(),
                    emptyList(),
                ),
            ),
            operations = mapOf(
                identity to MetadataDescribedTextWorkbench.Change.Replace(identity, "Nuevo"),
            ),
        )

        assertEquals(
            MetadataWorkbenchSaveBundlePolicy.Result.Blocked(
                MetadataWorkbenchSaveBundlePolicy.Reason.INVALID_DESCRIBED_TEXT,
            ),
            MetadataWorkbenchSaveBundlePolicy.build(
                MetadataReview.Unchanged,
                state,
                provisionalCount = 0,
            ),
        )
    }
}
