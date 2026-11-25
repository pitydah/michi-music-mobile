package org.michimusic.mobile.ui.components

import org.michimusic.core.metadata.MetadataArtworkAdditionPreview
import org.michimusic.core.metadata.MetadataDescribedTextWorkbench
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSelectiveAssetStage

internal object MetadataWorkbenchSaveBundlePolicy {
    enum class Reason {
        PROVISIONAL_FIELDS,
        INVALID_SCALAR_REVIEW,
        RESOURCES_NOT_INSPECTED,
        INVALID_DESCRIBED_TEXT,
        INVALID_ARTWORK_ADDITION,
        INCOMPLETE_ARTWORK_REPLACEMENT,
        TOO_MANY_RESOURCES,
        NO_EFFECTIVE_CHANGES,
    }

    sealed interface Result {
        data class Ready(
            val scalarReview: MetadataReview.Ready?,
            val resourceEdits: List<MetadataSelectiveAssetStage.Edit>,
        ) : Result

        data class Blocked(val reason: Reason) : Result
    }

    fun build(
        scalarReview: MetadataReview,
        artLyrics: MetadataArtLyricsUiState,
        provisionalCount: Int,
    ): Result {
        if (provisionalCount != 0) return Result.Blocked(Reason.PROVISIONAL_FIELDS)
        if (scalarReview is MetadataReview.Blocked)
            return Result.Blocked(Reason.INVALID_SCALAR_REVIEW)

        val fields = scalarReview as? MetadataReview.Ready
        val resources = buildList {
            when (val artwork = artLyrics.artworkEdit) {
                null -> {
                    if (artLyrics.artwork != null)
                        return Result.Blocked(Reason.INCOMPLETE_ARTWORK_REPLACEMENT)
                }
                is ArtworkEdit.Add -> {
                    val snapshot = artLyrics.inspected
                        ?: return Result.Blocked(Reason.RESOURCES_NOT_INSPECTED)
                    val proposal = (
                        MetadataArtworkAdditionPreview.review(
                            snapshot, artwork.bytes, artwork.mime,
                        ) as? MetadataArtworkAdditionPreview.Result.Ready
                        )?.proposal
                        ?: return Result.Blocked(Reason.INVALID_ARTWORK_ADDITION)
                    add(MetadataSelectiveAssetStage.Edit.PictureAdd(proposal))
                }
                is ArtworkEdit.Remove -> {
                    val snapshot = artLyrics.inspected
                        ?: return Result.Blocked(Reason.RESOURCES_NOT_INSPECTED)
                    val selected = snapshot.artworks.singleOrNull {
                        it.ordinal == artwork.ordinal
                    } ?: return Result.Blocked(Reason.INCOMPLETE_ARTWORK_REPLACEMENT)
                    if (selected.info.sha256 != artwork.sourceSha256)
                        return Result.Blocked(Reason.INCOMPLETE_ARTWORK_REPLACEMENT)
                    add(MetadataSelectiveAssetStage.Edit.PictureRemove(
                        artwork.ordinal, artwork.sourceSha256,
                    ))
                }
                is ArtworkEdit.Replace -> {
                    val proposal = artLyrics.artwork
                        ?: return Result.Blocked(Reason.INCOMPLETE_ARTWORK_REPLACEMENT)
                    if (proposal.index != artwork.ordinal ||
                        proposal.sourceSha256 != artwork.sourceSha256 ||
                        !proposal.proposedBytes.contentEquals(artwork.bytes)
                    ) return Result.Blocked(Reason.INCOMPLETE_ARTWORK_REPLACEMENT)
                    add(MetadataSelectiveAssetStage.Edit.Picture(proposal))
                }
            }

            if (artLyrics.operations.isNotEmpty()) {
                if (artLyrics.inspected == null)
                    return Result.Blocked(Reason.RESOURCES_NOT_INSPECTED)
                val reviewed = artLyrics.textResult
                if (reviewed !is MetadataDescribedTextWorkbench.Result.Ready ||
                    reviewed.differences.size != artLyrics.operations.size
                ) return Result.Blocked(Reason.INVALID_DESCRIBED_TEXT)
                artLyrics.operations.entries
                    .sortedWith(compareBy(
                        { it.key.kind.name },
                        { it.key.language },
                        { it.key.description },
                    ))
                    .forEach {
                        add(MetadataSelectiveAssetStage.Edit.DescribedText(it.value))
                    }
            }
        }
        if (resources.size > 4) return Result.Blocked(Reason.TOO_MANY_RESOURCES)
        if (fields == null && resources.isEmpty())
            return Result.Blocked(Reason.NO_EFFECTIVE_CHANGES)
        return Result.Ready(fields, resources)
    }
}
