package org.michimusic.core.metadata

/**
 * ME-07. Pure user-action boundary for a persistent metadata transaction.
 * A record surviving a crash is evidence of an INCOMPLETE operation, not a
 * permission to roll back, delete its backups or retry a source write.
 */
object MetadataRecoveryActionPolicy {
    enum class Action {
        NONE,
        PRIVATE_PREPARATION_CAN_BE_DISCARDED,
        KEEP_PRIVATE_ARTIFACTS,
    }
    enum class Reason {
        CLEAR, TARGET_NOT_CURRENT, WRONG_TARGET, JOURNAL_CORRUPT,
        PRIVATE_ARTIFACTS_INVALID, SOURCE_MAY_HAVE_CHANGED,
        PREPARED_ONLY,
    }
    data class Decision(val action: Action, val reason: Reason)

    /** Identity must be the full owner+track+URI tuple, never trackId alone. */
    fun inspect(
        journalReadable: Boolean,
        hasPending: Boolean,
        targetMatches: Boolean,
        privateArtifactsIntact: Boolean,
        phase: MetadataRecoveryPhase?,
        targetStillCurrent: Boolean,
    ): Decision = when {
        !journalReadable -> Decision(Action.KEEP_PRIVATE_ARTIFACTS, Reason.JOURNAL_CORRUPT)
        !hasPending -> Decision(Action.NONE, Reason.CLEAR)
        !targetMatches -> Decision(Action.KEEP_PRIVATE_ARTIFACTS, Reason.WRONG_TARGET)
        !privateArtifactsIntact -> Decision(Action.KEEP_PRIVATE_ARTIFACTS, Reason.PRIVATE_ARTIFACTS_INVALID)
        phase == null -> Decision(Action.KEEP_PRIVATE_ARTIFACTS, Reason.JOURNAL_CORRUPT)
        !targetStillCurrent -> Decision(Action.KEEP_PRIVATE_ARTIFACTS, Reason.TARGET_NOT_CURRENT)
        MetadataRecoveryPhasePolicy.sourceMayHaveChanged(phase) ->
            Decision(Action.KEEP_PRIVATE_ARTIFACTS, Reason.SOURCE_MAY_HAVE_CHANGED)
        MetadataRecoveryPhasePolicy.canDiscardPrivateOnly(phase) ->
            Decision(Action.PRIVATE_PREPARATION_CAN_BE_DISCARDED, Reason.PREPARED_ONLY)
        else -> Decision(Action.KEEP_PRIVATE_ARTIFACTS, Reason.JOURNAL_CORRUPT)
    }
}
