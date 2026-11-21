package org.michimusic.core.metadata

import java.net.URI

/**
 * ME-11Q: pure, conservative decision for ONE SAF rename index CAS.
 * A verified rename is NOT a grant to mutate Room: callers must obtain both
 * the durable terminal journal state and an INDEPENDENT second SAF readback.
 * This class has no Android, database, provider, or source-writing side effects.
 */
object MetadataSafRenameIndexPublicationPolicy {
    data class Indexed(
        val sourceId: String,
        val trackId: String,
        val filepath: String,
        val size: Long,
        val verifiedSize: Long,
        val verifiedSha256: String,
        val verifiedAt: Long,
        val verifiedAlgorithm: String,
    )

    data class Preconditions(
        val renameJournalTerminalVerified: Boolean,
        val mainRecoveryJournalClear: Boolean,
        val providerListingCertified: Boolean,
        /** An independently tested guarantee; track IDs may encode document IDs. */
        val trackIdStableAcrossDocumentRename: Boolean,
        val ownerStillCurrent: Boolean,
        val playbackClosed: Boolean,
    )

    enum class Reason {
        UNVERIFIED_JOURNAL, OTHER_RECOVERY_PENDING, UNCERTIFIED_PROVIDER,
        UNSTABLE_TRACK_ID, OWNER_CHANGED, PLAYBACK_ACTIVE,
        WRONG_INDEX_IDENTITY, WRONG_FILE_SIZE, INVALID_URI_OR_DOCUMENT,
        INDEPENDENT_READBACK_UNVERIFIED, INVALID_INDEX_PROOF,
    }

    sealed interface Decision {
        data class Refused(val reason: Reason) : Decision
        data class Ready(val previousUri: String, val currentUri: String, val newDocumentId: String) : Decision
    }

    private val sha = Regex("[0-9a-f]{64}")
    private fun authority(value: String): String? = try {
        val u = URI(value)
        if (u.scheme == "content" && !u.rawAuthority.isNullOrBlank() && u.fragment == null)
            u.rawAuthority else null
    } catch (_: Exception) { null }

    fun decide(
        intent: MetadataSafRenameRecoveryPolicy.Intent,
        independentVerdict: MetadataSafRenameRecoveryPolicy.Verdict,
        indexed: Indexed,
        conditions: Preconditions,
    ): Decision {
        fun no(reason: Reason): Decision = Decision.Refused(reason)
        if (!conditions.renameJournalTerminalVerified) return no(Reason.UNVERIFIED_JOURNAL)
        if (!conditions.mainRecoveryJournalClear) return no(Reason.OTHER_RECOVERY_PENDING)
        if (!conditions.providerListingCertified) return no(Reason.UNCERTIFIED_PROVIDER)
        if (!conditions.trackIdStableAcrossDocumentRename) return no(Reason.UNSTABLE_TRACK_ID)
        if (!conditions.ownerStillCurrent) return no(Reason.OWNER_CHANGED)
        if (!conditions.playbackClosed) return no(Reason.PLAYBACK_ACTIVE)
        if (indexed.sourceId != intent.sourceId || indexed.trackId != intent.trackId ||
            indexed.trackId.isBlank() || indexed.filepath != intent.beforeUri)
            return no(Reason.WRONG_INDEX_IDENTITY)
        if (indexed.size <= 0 || indexed.size > 1024L * 1024 * 1024 ||
            indexed.size != intent.originalBytes || !sha.matches(intent.originalSha256))
            return no(Reason.WRONG_FILE_SIZE)
        val renamed = independentVerdict as? MetadataSafRenameRecoveryPolicy.Verdict.RenamedVerified
            ?: return no(Reason.INDEPENDENT_READBACK_UNVERIFIED)
        val beforeAuthority = authority(intent.beforeUri)
        if (beforeAuthority == null || authority(intent.parentUri) != beforeAuthority ||
            authority(renamed.uri) != beforeAuthority || renamed.documentId.isBlank() ||
            intent.beforeDocumentId.isBlank() || intent.beforeName == intent.requestedName)
            return no(Reason.INVALID_URI_OR_DOCUMENT)
        // No invented verifiedAt, hash, or content length. A previous verified
        // cache row must refer to the SAME bytes, otherwise its proof is stale.
        val unverified = indexed.verifiedSize == 0L && indexed.verifiedAt == 0L &&
            indexed.verifiedSha256.isEmpty() && indexed.verifiedAlgorithm.isEmpty()
        val verified = indexed.verifiedSize == indexed.size && indexed.verifiedAt > 0L &&
            indexed.verifiedAlgorithm.equals("sha256", ignoreCase = true) &&
            indexed.verifiedSha256 == intent.originalSha256
        if (!unverified && !verified) return no(Reason.INVALID_INDEX_PROOF)
        return Decision.Ready(intent.beforeUri, renamed.uri, renamed.documentId)
    }
}
