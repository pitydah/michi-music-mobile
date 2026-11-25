package org.michimusic.core.metadata

import java.security.MessageDigest

/**
 * Exact semantic comparison for artwork and identity-addressed ID3 text.
 *
 * Whole-file candidate SHA remains the physical authority. This policy proves
 * that every selected resource has the durable before/after identity and that
 * every observable unselected resource is unchanged.
 */
object MetadataResourceSemanticPolicy {
    enum class Refusal {
        INVALID_INTENT,
        FORMAT_MISMATCH,
        ORIGINAL_RESOURCE_MISMATCH,
        CANDIDATE_RESOURCE_MISMATCH,
        UNSELECTED_RESOURCE_CHANGED,
    }

    sealed interface Verdict {
        data class ResourcesMatch(val verifiedChanges: Int) : Verdict
        data class Refused(val reason: Refusal) : Verdict
    }

    fun compare(
        intent: MetadataCommitIntentManifest.Receipt,
        original: MetadataArtworkLyricsProbe.Snapshot,
        candidate: MetadataArtworkLyricsProbe.Snapshot,
    ): Verdict {
        if (!MetadataCommitIntentManifest.valid(intent))
            return Verdict.Refused(Refusal.INVALID_INTENT)
        val expectedFormat = when (intent.container) {
            MetadataAudioContainer.ID3_V23 -> MetadataArtworkLyricsProbe.Format.ID3_V23
            MetadataAudioContainer.ID3_V24 -> MetadataArtworkLyricsProbe.Format.ID3_V24
            MetadataAudioContainer.FLAC -> MetadataArtworkLyricsProbe.Format.FLAC
            else -> return if (intent.resources.isEmpty()) Verdict.ResourcesMatch(0)
                else Verdict.Refused(Refusal.FORMAT_MISMATCH)
        }
        if (original.format != expectedFormat || candidate.format != expectedFormat)
            return Verdict.Refused(Refusal.FORMAT_MISMATCH)

        val expectedPictures = original.artworks.toMutableList()
        val selectedTexts = hashSetOf<MetadataDescribedTextWorkbench.Identity>()
        for (resource in intent.resources) when (resource) {
            is MetadataCommitIntentManifest.ResourceChange.PictureReplacement -> {
                val before = original.artworks.singleOrNull { it.ordinal == resource.ordinal }
                    ?: return Verdict.Refused(Refusal.ORIGINAL_RESOURCE_MISMATCH)
                if (!pictureMatches(
                        before, resource.format, resource.type, resource.description,
                        resource.beforeMime, resource.beforeBytes, resource.beforeSha256,
                    )
                ) return Verdict.Refused(Refusal.ORIGINAL_RESOURCE_MISMATCH)
                val index = expectedPictures.indexOfFirst { it.ordinal == resource.ordinal }
                if (index < 0) return Verdict.Refused(Refusal.ORIGINAL_RESOURCE_MISMATCH)
                expectedPictures[index] = generatedPicture(
                    resource.ordinal, resource.format, resource.type,
                    resource.description, resource.afterMime, resource.afterBytes,
                    resource.afterSha256,
                )
            }
            is MetadataCommitIntentManifest.ResourceChange.PictureAdd -> {
                if (resource.ordinal != expectedPictures.size)
                    return Verdict.Refused(Refusal.ORIGINAL_RESOURCE_MISMATCH)
                expectedPictures += generatedPicture(
                    resource.ordinal, resource.format, resource.type,
                    resource.description, resource.afterMime, resource.afterBytes,
                    resource.afterSha256,
                )
            }
            is MetadataCommitIntentManifest.ResourceChange.PictureRemove -> {
                val before = original.artworks.singleOrNull { it.ordinal == resource.ordinal }
                    ?: return Verdict.Refused(Refusal.ORIGINAL_RESOURCE_MISMATCH)
                if (!pictureMatches(
                        before, resource.format, resource.type, resource.description,
                        resource.beforeMime, resource.beforeBytes, resource.beforeSha256,
                    )
                ) return Verdict.Refused(Refusal.ORIGINAL_RESOURCE_MISMATCH)
                val index = expectedPictures.indexOfFirst { it.ordinal == resource.ordinal }
                if (index < 0) return Verdict.Refused(Refusal.ORIGINAL_RESOURCE_MISMATCH)
                expectedPictures.removeAt(index)
            }
            is MetadataCommitIntentManifest.ResourceChange.DescribedText -> {
                val identity = MetadataDescribedTextWorkbench.Identity(
                    resource.kind, resource.language, resource.description,
                )
                val before = original.texts.filter { it.identity == identity }
                val after = candidate.texts.filter { it.identity == identity }
                if (before.size != resource.beforeCount ||
                    MetadataCommitIntentManifest.valuesDigest(before.map { it.text }) !=
                    resource.beforeSha256
                ) return Verdict.Refused(Refusal.ORIGINAL_RESOURCE_MISMATCH)
                if (after.size != resource.afterCount ||
                    MetadataCommitIntentManifest.valuesDigest(after.map { it.text }) !=
                    resource.afterSha256
                ) return Verdict.Refused(Refusal.CANDIDATE_RESOURCE_MISMATCH)
                selectedTexts += identity
            }
        }

        val normalized = expectedPictures.mapIndexed { index, artwork ->
            if (artwork.ordinal == index) artwork else artwork.copy(ordinal = index)
        }
        if (candidate.artworks.size != normalized.size)
            return Verdict.Refused(Refusal.CANDIDATE_RESOURCE_MISMATCH)
        normalized.forEachIndexed { index, expected ->
            val actual = candidate.artworks[index]
            if (actual.ordinal != index || sha256(actual.image) != actual.info.sha256)
                return Verdict.Refused(Refusal.CANDIDATE_RESOURCE_MISMATCH)
            if (expected.image.isEmpty()) {
                if (!infoMatches(actual, expected))
                    return Verdict.Refused(Refusal.CANDIDATE_RESOURCE_MISMATCH)
            } else if (!samePicture(actual, expected)) {
                return Verdict.Refused(Refusal.UNSELECTED_RESOURCE_CHANGED)
            }
        }
        val oldTexts = original.texts.filterNot { it.identity in selectedTexts }
        val newTexts = candidate.texts.filterNot { it.identity in selectedTexts }
        if (oldTexts != newTexts)
            return Verdict.Refused(Refusal.UNSELECTED_RESOURCE_CHANGED)
        return Verdict.ResourcesMatch(intent.resources.size)
    }

    /**
     * Post-provider readback. The caller already proved the exact whole-file
     * candidate SHA; this independently proves every selected resource's
     * durable after-identity through the format parser.
     */
    fun compareCandidate(
        intent: MetadataCommitIntentManifest.Receipt,
        candidate: MetadataArtworkLyricsProbe.Snapshot,
    ): Verdict {
        if (!MetadataCommitIntentManifest.valid(intent))
            return Verdict.Refused(Refusal.INVALID_INTENT)
        val expectedFormat = when (intent.container) {
            MetadataAudioContainer.ID3_V23 -> MetadataArtworkLyricsProbe.Format.ID3_V23
            MetadataAudioContainer.ID3_V24 -> MetadataArtworkLyricsProbe.Format.ID3_V24
            MetadataAudioContainer.FLAC -> MetadataArtworkLyricsProbe.Format.FLAC
            else -> return if (intent.resources.isEmpty()) Verdict.ResourcesMatch(0)
                else Verdict.Refused(Refusal.FORMAT_MISMATCH)
        }
        if (candidate.format != expectedFormat)
            return Verdict.Refused(Refusal.FORMAT_MISMATCH)
        for (resource in intent.resources) when (resource) {
            is MetadataCommitIntentManifest.ResourceChange.PictureReplacement -> {
                val after = candidate.artworks.singleOrNull {
                    it.ordinal == resource.ordinal
                } ?: return Verdict.Refused(Refusal.CANDIDATE_RESOURCE_MISMATCH)
                if (!pictureMatches(
                        after, resource.format, resource.type, resource.description,
                        resource.afterMime, resource.afterBytes, resource.afterSha256,
                    )
                ) return Verdict.Refused(Refusal.CANDIDATE_RESOURCE_MISMATCH)
            }
            is MetadataCommitIntentManifest.ResourceChange.PictureAdd -> {
                val after = candidate.artworks.singleOrNull {
                    it.ordinal == resource.ordinal
                } ?: return Verdict.Refused(Refusal.CANDIDATE_RESOURCE_MISMATCH)
                if (!pictureMatches(
                        after, resource.format, resource.type, resource.description,
                        resource.afterMime, resource.afterBytes, resource.afterSha256,
                    )
                ) return Verdict.Refused(Refusal.CANDIDATE_RESOURCE_MISMATCH)
            }
            is MetadataCommitIntentManifest.ResourceChange.PictureRemove -> Unit
            is MetadataCommitIntentManifest.ResourceChange.DescribedText -> {
                val identity = MetadataDescribedTextWorkbench.Identity(
                    resource.kind, resource.language, resource.description,
                )
                val after = candidate.texts.filter { it.identity == identity }
                if (after.size != resource.afterCount ||
                    MetadataCommitIntentManifest.valuesDigest(after.map { it.text }) !=
                    resource.afterSha256
                ) return Verdict.Refused(Refusal.CANDIDATE_RESOURCE_MISMATCH)
            }
        }
        return Verdict.ResourcesMatch(intent.resources.size)
    }

    private fun generatedPicture(
        ordinal: Int,
        format: MetadataArtworkEvidence.Format,
        type: Int,
        description: String,
        mime: String,
        bytes: Int,
        digest: String,
    ): MetadataArtworkLyricsProbe.Artwork = MetadataArtworkLyricsProbe.Artwork(
        ordinal,
        MetadataArtworkEvidence.Picture(
            format = format, type = type, mime = mime, bytes = bytes,
            sha256 = digest, description = description,
        ),
        ByteArray(0),
    )

    private fun infoMatches(
        actual: MetadataArtworkLyricsProbe.Artwork,
        expected: MetadataArtworkLyricsProbe.Artwork,
    ): Boolean = actual.info.format == expected.info.format &&
        actual.info.type == expected.info.type &&
        actual.info.description == expected.info.description &&
        actual.info.mime == expected.info.mime &&
        actual.info.bytes == expected.info.bytes &&
        actual.info.sha256 == expected.info.sha256 &&
        actual.image.size == expected.info.bytes

    private fun pictureMatches(
        artwork: MetadataArtworkLyricsProbe.Artwork,
        format: MetadataArtworkEvidence.Format,
        type: Int,
        description: String,
        mime: String,
        bytes: Int,
        digest: String,
    ): Boolean = artwork.info.format == format &&
        artwork.info.type == type &&
        artwork.info.description == description &&
        artwork.info.mime == mime &&
        artwork.info.bytes == bytes &&
        artwork.image.size == bytes &&
        artwork.info.sha256 == digest &&
        sha256(artwork.image) == digest

    private fun samePicture(
        before: MetadataArtworkLyricsProbe.Artwork,
        after: MetadataArtworkLyricsProbe.Artwork,
    ): Boolean = before.ordinal == after.ordinal &&
        before.info == after.info &&
        before.image.contentEquals(after.image)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
}
