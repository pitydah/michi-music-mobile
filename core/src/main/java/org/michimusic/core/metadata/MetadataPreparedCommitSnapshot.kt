package org.michimusic.core.metadata

/**
 * ME-10L: immutable description of a JOURNALED private backup+candidate pair.
 * This proves only structural eligibility for a subsequent provider preflight.
 * It cannot elevate V2 private preview receipts, grant permissions, open an FD,
 * transition the journal to MUTATING, or declare a source write certified.
 */
object MetadataPreparedCommitSnapshot {
    enum class Reason {
        NOT_STAGE_READY, INVALID_TRANSACTION, INVALID_TARGET,
        INVALID_SOURCE_FINGERPRINT, INVALID_CANDIDATE_FINGERPRINT,
        IDENTICAL_BYTES, UNSUPPORTED_FORMAT,
    }

    data class Input(
        val transactionId: String,
        val phase: MetadataRecoveryPhase,
        val sourceId: String,
        val trackId: String,
        val uri: String,
        val originalBytes: Long,
        val originalSha256: String,
        val candidateBytes: Long,
        val candidateSha256: String,
        val format: String,
    )

    sealed interface Decision {
        data class Refused(val reason: Reason) : Decision
        data class Candidate(val evidence: Evidence) : Decision
    }

    /** No V2 preview owner hash can be used in place of the journaled original. */
    data class Evidence internal constructor(
        val transactionId: String,
        val sourceId: String,
        val trackId: String,
        val uri: String,
        val format: MetadataAudioContainer,
        val originalBytes: Long,
        val originalSha256: String,
        val candidateBytes: Long,
        val candidateSha256: String,
    ) {
        val canCommitToProvider: Boolean get() = false
    }

    private const val MAX_SOURCE_BYTES = 1024L * 1024L * 1024L
    private const val MAX_CANDIDATE_BYTES = 1024L * 1024L * 1024L
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val digest = Regex("[a-f0-9]{64}")

    fun evaluate(input: Input): Decision {
        if (input.phase != MetadataRecoveryPhase.STAGE_READY)
            return Decision.Refused(Reason.NOT_STAGE_READY)
        if (!uuid.matches(input.transactionId))
            return Decision.Refused(Reason.INVALID_TRANSACTION)
        if (input.trackId.isBlank() || !input.uri.startsWith("content://") ||
            input.uri.length > 8192 || input.sourceId.length > 8192 || input.trackId.length > 8192)
            return Decision.Refused(Reason.INVALID_TARGET)
        if (input.originalBytes !in 12..MAX_SOURCE_BYTES || !digest.matches(input.originalSha256))
            return Decision.Refused(Reason.INVALID_SOURCE_FINGERPRINT)
        if (input.candidateBytes !in 12..MAX_CANDIDATE_BYTES || !digest.matches(input.candidateSha256))
            return Decision.Refused(Reason.INVALID_CANDIDATE_FINGERPRINT)
        if (input.originalBytes == input.candidateBytes && input.originalSha256 == input.candidateSha256)
            return Decision.Refused(Reason.IDENTICAL_BYTES)
        val kind = when (input.format) {
            MetadataAudioContainer.FLAC.name -> MetadataAudioContainer.FLAC
            MetadataAudioContainer.ID3_V23.name -> MetadataAudioContainer.ID3_V23
            MetadataAudioContainer.ID3_V24.name -> MetadataAudioContainer.ID3_V24
            MetadataAudioContainer.WAVPACK.name -> MetadataAudioContainer.WAVPACK
            MetadataAudioContainer.APE.name -> MetadataAudioContainer.APE
            MetadataAudioContainer.WAV.name -> MetadataAudioContainer.WAV
            MetadataAudioContainer.AIFF.name -> MetadataAudioContainer.AIFF
            // OGG and MP4 are stage-only containers in this patch: their
            // rewriters remux the container, so they carry no certified
            // in-place writer and must never enter the source-mutation
            // preflight. Do not guess support from the file extension.
            else -> return Decision.Refused(Reason.UNSUPPORTED_FORMAT)
        }
        return Decision.Candidate(Evidence(input.transactionId, input.sourceId, input.trackId,
            input.uri, kind, input.originalBytes, input.originalSha256,
            input.candidateBytes, input.candidateSha256))
    }
}
