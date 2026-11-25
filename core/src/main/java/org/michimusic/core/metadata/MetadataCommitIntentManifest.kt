package org.michimusic.core.metadata

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * ME-10N: exact, owner-bound edit intent for the JOURNAL V1 prepared candidate.
 * A private V2 preview receipt cannot replace this manifest. Digests protect
 * identity against accidental/stale selection, not against a hostile app process.
 * This record does not certify a provider, an Android permission or a write.
 */
object MetadataCommitIntentManifest {
    private val sha = Regex("[a-f0-9]{64}")
    private val tx = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private const val MAX_FIELDS = 64
    private const val MAX_RESOURCES = 4
    private const val MAX_BYTES = 1024L * 1024L * 1024L

    data class Change(
        val field: MetadataField,
        val beforeCount: Int,
        val afterCount: Int,
        val beforeSha256: String,
        val afterSha256: String,
    )

    sealed interface ResourceChange {
        data class PictureReplacement(
            val ordinal: Int,
            val format: MetadataArtworkEvidence.Format,
            val type: Int,
            val description: String,
            val beforeMime: String,
            val beforeBytes: Int,
            val beforeSha256: String,
            val afterMime: String,
            val afterBytes: Int,
            val afterSha256: String,
        ) : ResourceChange

        data class DescribedText(
            val kind: MetadataId3DescribedTagCodec.Kind,
            val language: String,
            val description: String,
            val beforeCount: Int,
            val afterCount: Int,
            val beforeSha256: String,
            val afterSha256: String,
        ) : ResourceChange
    }

    data class Receipt(
        val transactionId: String,
        val ownerSha256: String,
        val provider: MetadataDocumentProvider,
        val container: MetadataAudioContainer,
        val originalBytes: Long,
        val originalSha256: String,
        val candidateBytes: Long,
        val candidateSha256: String,
        val changes: List<Change>,
        val resources: List<ResourceChange> = emptyList(),
    ) {
        /** No receipt, even if structurally valid, can turn on a source writer. */
        val sourceWriteCertified: Boolean get() = false
    }

    enum class Refusal {
        INVALID_MANIFEST, OWNER_MISMATCH, TRANSACTION_MISMATCH,
        CONTAINER_MISMATCH, PROVIDER_MISMATCH, BYTE_EVIDENCE_MISMATCH,
        FIELD_NOT_STAGED,
    }
    sealed interface Verdict {
        data object BoundForExternalReview : Verdict
        data class Refused(val reason: Refusal) : Verdict
    }

    private fun updateString(md: MessageDigest, value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        md.update(ByteBuffer.allocate(4).putInt(encoded.size).array())
        md.update(encoded)
    }

    /** Framed, ordered lists: ["Jazz; Fusion"] cannot alias ["Jazz", "Fusion"]. */
    fun valuesDigest(values: List<String>): String {
        require(values.size <= 32 && values.all { it.length <= 65_536 })
        val md = MessageDigest.getInstance("SHA-256")
        updateString(md, "MICHI_METADATA_INTENT_VALUES_V1")
        md.update(ByteBuffer.allocate(4).putInt(values.size).array())
        for (value in values) updateString(md, value)
        return md.digest().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }

    /** Build only from a freshly validated review and the already hashed stage. */
    fun create(
        transactionId: String,
        review: MetadataReview.Ready,
        provider: MetadataDocumentProvider,
        container: MetadataAudioContainer,
        originalBytes: Long,
        originalSha256: String,
        candidateBytes: Long,
        candidateSha256: String,
    ): Receipt {
        val operations = review.diffs.map { diff ->
            if (diff.after.isEmpty()) MetadataChange.Clear(diff.field)
            else MetadataChange.Replace(diff.field, diff.after)
        }
        require(MetadataEditPreview.review(review.original, operations) == review) {
            "INTENT_REVIEW_NOT_REPRODUCIBLE"
        }
        require(review.original.target == review.proposed.target)
        val changes = review.diffs.sortedBy { it.field.name }.map { diff ->
            require(diff.before == review.original.known[diff.field].orEmpty())
            require(diff.after == review.proposed.known[diff.field].orEmpty())
            Change(diff.field, diff.before.size, diff.after.size,
                valuesDigest(diff.before), valuesDigest(diff.after))
        }
        val receipt = Receipt(transactionId,
            MetadataPrivateSessionIdentity.digest(review.original.target),
            provider, container, originalBytes, originalSha256,
            candidateBytes, candidateSha256, changes)
        require(valid(receipt)) { "INVALID_PREPARED_INTENT" }
        return receipt
    }

    /**
     * Builds the durable scalar/resource intent from the exact observed source.
     * Raw artwork and text never enter the receipt; only bounded identities and
     * framed digests survive process death.
     */
    fun createBundle(
        transactionId: String,
        target: MetadataTarget,
        review: MetadataReview.Ready?,
        resources: MetadataArtworkLyricsProbe.Snapshot,
        edits: List<MetadataSelectiveAssetStage.Edit>,
        provider: MetadataDocumentProvider,
        container: MetadataAudioContainer,
        originalBytes: Long,
        originalSha256: String,
        candidateBytes: Long,
        candidateSha256: String,
    ): Receipt {
        require(review == null || review.original.target == target)
        val scalar = review?.let {
            create(
                transactionId, it, provider, container, originalBytes, originalSha256,
                candidateBytes, candidateSha256,
            ).changes
        }.orEmpty()
        require(edits.size <= MAX_RESOURCES)
        val resourceChanges = edits.map { edit ->
            when (edit) {
                is MetadataSelectiveAssetStage.Edit.Picture -> {
                    val proposal = edit.proposal
                    val before = resources.artworks.singleOrNull {
                        it.ordinal == proposal.index
                    } ?: throw IllegalArgumentException("RESOURCE_PICTURE_NOT_OBSERVED")
                    require(before.info.sha256 == proposal.sourceSha256)
                    require(before.info.bytes == before.image.size)
                    require(digestBytes(before.image) == before.info.sha256)
                    require(proposal.proposedBytes.size == proposal.proposedSize)
                    require(digestBytes(proposal.proposedBytes) == proposal.proposedSha256)
                    ResourceChange.PictureReplacement(
                        before.ordinal, before.info.format, before.info.type,
                        before.info.description, before.info.mime, before.info.bytes,
                        before.info.sha256, proposal.proposedMime, proposal.proposedSize,
                        proposal.proposedSha256,
                    )
                }
                is MetadataSelectiveAssetStage.Edit.DescribedText -> {
                    require(resources.format != MetadataArtworkLyricsProbe.Format.FLAC)
                    val operation = edit.change
                    val before = resources.texts.singleOrNull {
                        it.identity == operation.identity
                    } ?: throw IllegalArgumentException("RESOURCE_TEXT_NOT_OBSERVED")
                    val reviewed = MetadataDescribedTextWorkbench.review(
                        true, resources.texts, listOf(operation),
                    )
                    require(
                        reviewed is MetadataDescribedTextWorkbench.Result.Ready &&
                            reviewed.differences.size == 1,
                    ) { "RESOURCE_TEXT_NOT_CHANGED" }
                    val after = when (operation) {
                        is MetadataDescribedTextWorkbench.Change.Replace -> operation.text
                        is MetadataDescribedTextWorkbench.Change.Clear -> null
                    }
                    ResourceChange.DescribedText(
                        before.identity.kind, before.identity.language,
                        before.identity.description, 1, if (after == null) 0 else 1,
                        valuesDigest(listOf(before.text)),
                        valuesDigest(after?.let(::listOf).orEmpty()),
                    )
                }
            }
        }.sortedBy(::resourceKey)
        require(resourceChanges.map(::resourceKey).distinct().size == resourceChanges.size) {
            "DUPLICATE_RESOURCE_TARGET"
        }
        val receipt = Receipt(
            transactionId, MetadataPrivateSessionIdentity.digest(target), provider, container,
            originalBytes, originalSha256, candidateBytes, candidateSha256, scalar,
            resourceChanges,
        )
        require(valid(receipt)) { "INVALID_PREPARED_INTENT" }
        return receipt
    }

    private fun digestBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }

    private fun resourceKey(change: ResourceChange): String = when (change) {
        is ResourceChange.PictureReplacement -> "0:${change.format}:${change.ordinal}"
        is ResourceChange.DescribedText ->
            "1:${change.kind}:${change.language}:${change.description}"
    }

    /** Strict structural checks also apply to decoded untrusted bytes. */
    fun valid(r: Receipt): Boolean {
        if (!tx.matches(r.transactionId) || !sha.matches(r.ownerSha256) ||
            !sha.matches(r.originalSha256) || !sha.matches(r.candidateSha256) ||
            r.originalBytes !in 12..MAX_BYTES || r.candidateBytes !in 12..MAX_BYTES ||
            (r.originalBytes == r.candidateBytes && r.originalSha256 == r.candidateSha256) ||
            r.provider !in setOf(MetadataDocumentProvider.SAF, MetadataDocumentProvider.MEDIASTORE) ||
            r.container !in setOf(MetadataAudioContainer.FLAC,
                MetadataAudioContainer.ID3_V23, MetadataAudioContainer.ID3_V24,
                MetadataAudioContainer.WAVPACK, MetadataAudioContainer.APE,
                MetadataAudioContainer.WAV, MetadataAudioContainer.AIFF,
                MetadataAudioContainer.OGG_OPUS, MetadataAudioContainer.OGG_VORBIS,
                MetadataAudioContainer.MP4_M4A) ||
            (r.changes.isEmpty() && r.resources.isEmpty()) ||
            r.changes.size > MAX_FIELDS || r.resources.size > MAX_RESOURCES) return false
        val supported = when (r.container) {
            MetadataAudioContainer.FLAC -> MetadataCapabilityRegistry.flacStageFields
            MetadataAudioContainer.WAVPACK, MetadataAudioContainer.APE ->
                MetadataCapabilityRegistry.apeStageFields
            MetadataAudioContainer.WAV -> MetadataCapabilityRegistry.wavStageFields
            MetadataAudioContainer.AIFF -> MetadataCapabilityRegistry.aiffStageFields
            MetadataAudioContainer.OGG_OPUS, MetadataAudioContainer.OGG_VORBIS ->
                MetadataCapabilityRegistry.oggStageFields
            MetadataAudioContainer.MP4_M4A -> MetadataCapabilityRegistry.mp4StageFields
            else -> MetadataCapabilityRegistry.id3StageFields
        }
        if (r.changes.map { it.field.name } != r.changes.map { it.field.name }.sorted() ||
            r.changes.map { it.field }.distinct().size != r.changes.size) return false
        val scalarValid = r.changes.all { c ->
            c.field in supported && c.beforeCount in 0..32 && c.afterCount in 0..32 &&
                c.beforeCount != -1 && c.afterCount != -1 &&
                (c.field.multiple || (c.beforeCount <= 1 && c.afterCount <= 1)) &&
                (when (r.container) {
                    MetadataAudioContainer.MP4_M4A ->
                        c.beforeCount <= 1 && c.afterCount <= 1
                    MetadataAudioContainer.WAV, MetadataAudioContainer.AIFF,
                    MetadataAudioContainer.OGG_OPUS, MetadataAudioContainer.OGG_VORBIS ->
                        c.beforeCount == 1 && c.afterCount == 1
                    MetadataAudioContainer.ID3_V23 ->
                        c.beforeCount <= 1 && c.afterCount <= 1
                    MetadataAudioContainer.ID3_V24 ->
                        c.afterCount <= 1 ||
                            (c.field.multiple &&
                                MetadataId3StageRewriter.preservationFrameId(c.field) != null)
                    else -> true
                }) &&
                sha.matches(c.beforeSha256) && sha.matches(c.afterSha256) &&
                (c.beforeCount != c.afterCount || c.beforeSha256 != c.afterSha256)
        }
        if (!scalarValid ||
            r.resources.map(::resourceKey) != r.resources.map(::resourceKey).sorted() ||
            r.resources.map(::resourceKey).distinct().size != r.resources.size
        ) return false
        return r.resources.all { resource ->
            when (resource) {
                is ResourceChange.PictureReplacement -> {
                    val expected = when (r.container) {
                        MetadataAudioContainer.ID3_V23, MetadataAudioContainer.ID3_V24 ->
                            MetadataArtworkEvidence.Format.ID3_APIC
                        MetadataAudioContainer.FLAC ->
                            MetadataArtworkEvidence.Format.FLAC_PICTURE
                        else -> null
                    }
                    expected == resource.format && resource.ordinal in 0..5 &&
                        resource.type in 0..20 &&
                        resource.description.length <= 2048 &&
                        resource.description.none { it.isISOControl() } &&
                        resource.beforeMime in setOf("image/jpeg", "image/png") &&
                        resource.afterMime in setOf("image/jpeg", "image/png") &&
                        resource.beforeBytes in 4..MetadataArtworkReplacementPreview.MAX_IMAGE_BYTES &&
                        resource.afterBytes in 16..MetadataArtworkReplacementPreview.MAX_IMAGE_BYTES &&
                        sha.matches(resource.beforeSha256) &&
                        sha.matches(resource.afterSha256) &&
                        resource.beforeSha256 != resource.afterSha256
                }
                is ResourceChange.DescribedText ->
                    r.container in setOf(
                        MetadataAudioContainer.ID3_V23,
                        MetadataAudioContainer.ID3_V24,
                    ) &&
                        resource.language.matches(Regex("[a-z]{3}")) &&
                        resource.description.length <= 128 &&
                        resource.description.none { it.isISOControl() } &&
                        resource.beforeCount == 1 && resource.afterCount in 0..1 &&
                        sha.matches(resource.beforeSha256) &&
                        sha.matches(resource.afterSha256) &&
                        (resource.beforeCount != resource.afterCount ||
                            resource.beforeSha256 != resource.afterSha256)
            }
        }
    }

    /** Bound means *only* that a record describes this exact prepared candidate. */
    fun verify(
        receipt: Receipt,
        transactionId: String,
        target: MetadataTarget,
        provider: MetadataDocumentProvider,
        container: MetadataAudioContainer,
        originalBytes: Long,
        originalSha256: String,
        candidateBytes: Long,
        candidateSha256: String,
    ): Verdict {
        if (!valid(receipt)) return Verdict.Refused(Refusal.INVALID_MANIFEST)
        if (receipt.transactionId != transactionId)
            return Verdict.Refused(Refusal.TRANSACTION_MISMATCH)
        val owner = try { MetadataPrivateSessionIdentity.digest(target) }
            catch (_: IllegalArgumentException) { return Verdict.Refused(Refusal.OWNER_MISMATCH) }
        if (receipt.ownerSha256 != owner) return Verdict.Refused(Refusal.OWNER_MISMATCH)
        if (receipt.container != container) return Verdict.Refused(Refusal.CONTAINER_MISMATCH)
        if (receipt.provider != provider) return Verdict.Refused(Refusal.PROVIDER_MISMATCH)
        if (receipt.originalBytes != originalBytes || receipt.originalSha256 != originalSha256 ||
            receipt.candidateBytes != candidateBytes || receipt.candidateSha256 != candidateSha256)
            return Verdict.Refused(Refusal.BYTE_EVIDENCE_MISMATCH)
        return Verdict.BoundForExternalReview
    }
}
