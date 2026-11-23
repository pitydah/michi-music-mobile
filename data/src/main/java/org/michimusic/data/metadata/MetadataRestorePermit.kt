package org.michimusic.data.metadata

import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataPrivateSessionIdentity
import org.michimusic.core.metadata.MetadataTarget

/**
 * ME-13 S5: an exact, transaction-bound authorization to restore the journal
 * backup of a local MediaStore MP3. It replaces the two global provider/writer
 * booleans ONLY for this precise transaction; SAF, FLAC and MP4 cannot use it.
 *
 * The permit is not a receipt and not a Room authorization. It is minted from a
 * durable intent + journal pair, and the coordinator re-reads the journal, the
 * private artifacts, the provider bytes and the exact write grant before any
 * provider descriptor is opened in write mode.
 */
internal data class MetadataRestorePermit(
    val transactionId: String,
    val sourceId: String,
    val trackId: String,
    val uri: String,
    val originalSha256: String,
    val originalBytes: Long,
    val candidateSha256: String,
    val stageBytes: Long,
    val container: String,
    val revision: Int,
    val ownerEvidenceToken: String,
    val explicitUserAction: Boolean,
) {
    fun authorizes(transactionId: String, target: MetadataTarget): Boolean =
        explicitUserAction && this.transactionId == transactionId &&
            sourceId == LOCAL_SOURCE && trackId.isNotBlank() &&
            uri.startsWith("content://media/") &&
            sourceId == target.sourceId && trackId == target.trackId && uri == target.uri &&
            ownerEvidenceToken.isNotBlank() &&
            ownerEvidenceToken == target.evidenceToken &&
            profileId == MediaStoreMp3TextProfile.ID &&
            evidenceId == MediaStoreMp3TextProfile.EVIDENCE_ID &&
            container in setOf(
                MetadataAudioContainer.ID3_V23.name, MetadataAudioContainer.ID3_V24.name) &&
            (container == MetadataAudioContainer.ID3_V23.name && revision == 3 ||
                container == MetadataAudioContainer.ID3_V24.name && revision == 4) &&
            originalBytes in 12..MAX_BYTES && stageBytes in 12..MAX_BYTES &&
            originalSha256.matches(HEX) && candidateSha256.matches(HEX) &&
            (originalBytes != stageBytes || originalSha256 != candidateSha256)

    fun matches(entry: MetadataRecoveryJournalV1.Entry): Boolean =
        explicitUserAction &&
            transactionId == entry.id && sourceId == entry.sourceId &&
            trackId == entry.trackId && uri == entry.uri &&
            originalSha256 == entry.originalSha256 && originalBytes == entry.expectedBytes &&
            candidateSha256 == entry.stageSha256 && stageBytes == entry.stageBytes &&
            container == entry.format && uri.startsWith("content://media/")

    private val profileId: String get() = MediaStoreMp3TextProfile.ID
    private val evidenceId: String get() = MediaStoreMp3TextProfile.EVIDENCE_ID

    companion object {
        const val LOCAL_SOURCE = "lib_device_local"
        private const val MAX_BYTES = 1024L * 1024L * 1024L
        private val HEX = Regex("[a-f0-9]{64}")

        /**
         * Mint only from the durable journal + intent pair and the exact target
         * whose opaque token is already bound into the owner digest. No UI draft
         * and no caller-supplied digest can create the permit.
         */
        fun fromDurable(
            entry: MetadataRecoveryJournalV1.Entry,
            intent: MetadataCommitIntentManifest.Receipt,
            explicitUserAction: Boolean,
        ): MetadataRestorePermit? {
            if (entry.ownerEvidenceToken.isBlank()) return null
            return fromDurable(
                entry, intent,
                MetadataTarget(entry.sourceId, entry.trackId, entry.uri,
                    entry.ownerEvidenceToken),
                explicitUserAction,
            )
        }

        fun fromDurable(
            entry: MetadataRecoveryJournalV1.Entry,
            intent: MetadataCommitIntentManifest.Receipt,
            target: MetadataTarget,
            explicitUserAction: Boolean,
        ): MetadataRestorePermit? {
            if (!explicitUserAction || !MetadataCommitIntentManifest.valid(intent)) return null
            if (intent.provider != MetadataDocumentProvider.MEDIASTORE) return null
            if (intent.transactionId != entry.id) return null
            if (intent.container !in setOf(
                    MetadataAudioContainer.ID3_V23, MetadataAudioContainer.ID3_V24)) return null
            if (entry.format != intent.container.name) return null
            if (intent.originalBytes != entry.expectedBytes ||
                intent.originalSha256 != entry.originalSha256 ||
                intent.candidateBytes != entry.stageBytes ||
                intent.candidateSha256 != entry.stageSha256) return null
            if (target.sourceId != entry.sourceId || target.trackId != entry.trackId ||
                target.uri != entry.uri || target.evidenceToken.isBlank()) return null
            val owner = try { MetadataPrivateSessionIdentity.digest(target) }
                catch (_: Exception) { return null }
            if (owner != intent.ownerSha256) return null
            val revision = when (intent.container) {
                MetadataAudioContainer.ID3_V23 -> 3
                MetadataAudioContainer.ID3_V24 -> 4
                else -> return null
            }
            return MetadataRestorePermit(
                transactionId = entry.id, sourceId = entry.sourceId,
                trackId = entry.trackId, uri = entry.uri,
                originalSha256 = entry.originalSha256, originalBytes = entry.expectedBytes,
                candidateSha256 = entry.stageSha256, stageBytes = entry.stageBytes,
                container = entry.format, revision = revision,
                ownerEvidenceToken = target.evidenceToken,
                explicitUserAction = true,
            ).takeIf { it.matches(entry) && it.authorizes(entry.id, target) }
        }
    }
}
