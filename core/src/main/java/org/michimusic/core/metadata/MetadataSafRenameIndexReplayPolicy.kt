package org.michimusic.core.metadata

/**
 * ME-11R. Read-only interpretation after an interrupted SAF -> Room publication.
 * The provider and Room are not one atomic transaction. Never treat a new URI in
 * the cache as proof that THIS transaction committed: a concurrent index update
 * or a crash after CAS can produce the same observation. Only an explicit,
 * independently audited recovery may clear the durable rename journal.
 */
object MetadataSafRenameIndexReplayPolicy {
    enum class Reason {
        JOURNAL_NOT_TERMINAL, MAIN_RECOVERY_PENDING, OWNER_CHANGED,
        PLAYBACK_ACTIVE, INDEPENDENT_RENAME_NOT_VERIFIED,
        INDEX_OWNER_MISMATCH, INDEX_MISSING, INDEX_CHANGED,
    }

    sealed interface Decision {
        /** The old URI is still indexed; run the normal full, conditional CAS policy. */
        data object EvaluateFirstCas : Decision
        /** Could be a completed CAS or an unrelated mutation. NEVER retry or claim success. */
        data object ManualPostCasReconciliation : Decision
        data class Refused(val reason: Reason) : Decision
    }

    fun classify(
        intent: MetadataSafRenameRecoveryPolicy.Intent,
        independentVerdict: MetadataSafRenameRecoveryPolicy.Verdict,
        indexed: MetadataSafRenameIndexPublicationPolicy.Indexed?,
        journalTerminal: Boolean,
        mainRecoveryClear: Boolean,
        ownerStillCurrent: Boolean,
        playbackClosed: Boolean,
    ): Decision {
        fun no(reason: Reason) = Decision.Refused(reason)
        if (!journalTerminal) return no(Reason.JOURNAL_NOT_TERMINAL)
        if (!mainRecoveryClear) return no(Reason.MAIN_RECOVERY_PENDING)
        if (!ownerStillCurrent) return no(Reason.OWNER_CHANGED)
        if (!playbackClosed) return no(Reason.PLAYBACK_ACTIVE)
        val renamed = independentVerdict as? MetadataSafRenameRecoveryPolicy.Verdict.RenamedVerified
            ?: return no(Reason.INDEPENDENT_RENAME_NOT_VERIFIED)
        if (indexed == null) return no(Reason.INDEX_MISSING)
        if (indexed.sourceId != intent.sourceId || indexed.trackId != intent.trackId ||
            indexed.trackId.isBlank() || indexed.size != intent.originalBytes)
            return no(Reason.INDEX_OWNER_MISMATCH)
        // Equal before/after URI is legitimate on providers that retain document IDs.
        // The BEFORE branch must win even if the provider returns the same URI.
        if (indexed.filepath == intent.beforeUri) return Decision.EvaluateFirstCas
        if (indexed.filepath == renamed.uri) return Decision.ManualPostCasReconciliation
        return no(Reason.INDEX_CHANGED)
    }
}
